package dev.hermit.crab.engine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.http.HostNames;
import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.validate.AuthenticatorChecks;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.AuthExport;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.common.template.UndefinedVariable;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.store.Origin;

public final class RuleEngine implements ConchExecutor {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    /** 能力请求被上游以 HTTP 400 拒绝。不是规则失配，不计入回滚。 */
    static final String UPSTREAM_INPUT_REJECTED = "上游拒绝了输入（HTTP 400）。请修改参数后重试。";

    /** 这次请求现取的入参、凭证、配置和时钟。待输入登录不保存，接着登录也不覆盖回去。 */
    private static final Set<String> EPHEMERAL_VARS = Set.of("input", "credential", "config", "now", "login");

    /** {@code boundEpoch} 的取值：这个包被判为会话失效，下一次要先探授权器。 */
    private static final long LINK_LOST = -1L;

    /** 登录 assert cookie 失败时给调用方的说明：原因，以及下一步。 */
    private static final String LOGIN_COOKIE_MISSING =
            "登录未建立会话：上游没有发回会话 Cookie。请核对用户名和密码；若凭证正确，请重新测试登录，或检查登录页是否已改版。";

    /** 暂存区下载链接（不需要 API Key）在步骤路径和错误信息中的兜底脱敏模式，与 DevTraceDraft 一致。 */
    private static final Pattern DOWNLOAD_TOKEN = Pattern.compile(
            "((?:(?i:/|%2f)|\\\\/)api(?:(?i:/|%2f)|\\\\/)-(?:(?i:/|%2f)|\\\\/)files(?:(?i:/|%2f)|\\\\/))[A-Za-z0-9_-]{16,}");
    private static final String DOWNLOAD_MASK = "$1***";

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
    private volatile SessionContention contention;
    private volatile AuthBindings authBindings = AuthBindings.NONE;

    /** 业务包没绑定、绑定失效或授权器不可用时给调用方的说明。不带授权器的包名和账号名。 */
    static final String LINK_MISSING = "这个账号还没有绑定可用的登录授权，请在 Crab 控制台里绑定";
    static final String LINK_PAUSED = "该账号已暂停自动登录";

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

    /** 授权器绑定。没接上时业务包都不能调用。 */
    public void useAuthBindings(AuthBindings authBindings) {
        this.authBindings = authBindings == null ? AuthBindings.NONE : authBindings;
    }

    /** 同网站的规则包互相顶掉登录的计数。没绑定时不记。 */
    public void bindContention(SessionContention contention) {
        this.contention = contention;
    }

    private void noteLogin(LoginScope scope) {
        SessionContention sink = contention;
        if (sink != null) {
            for (String address : scope.addresses()) {
                sink.loggedIn(address, scope.key);
            }
        }
    }

    private void noteLost(LoginScope scope) {
        SessionContention sink = contention;
        if (sink != null) {
            for (String address : scope.addresses()) {
                sink.lost(address, scope.key);
            }
        }
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
        CallSite.begin(request);
        DevTraceScope.begin(openDraft(request, authorized));
        try {
            return executeCaptured(request, authorized);
        } finally {
            DevTraceScope.end();
            CallSite.end();
            CallSecrets.end();
        }
    }

    private ExecutionResult executeCaptured(ExecutionRequest request, ConchDoc authorized) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        SessionKey key = null;
        Handoff handoff = null;
        URI traceBase = null;
        try {
            ConchDoc conch = authorizedConch(request, authorized);
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start, null);
            }
            traceBase = tryBaseUri(conch, config);
            version = conch.version().toString();
            Action action = conch.actions().get(request.action());
            if (action == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "能力不存在", null, null, "能力不存在",
                        List.of(), version, request, credentials, List.of(), start, traceBase);
            }
            Map<String, Object> input;
            try {
                input = inputs.prepare(action, request.rawInput());
            } catch (InvalidInput ex) {
                return finish(false, null, ErrorCode.INVALID_INPUT, ex.getMessage(), null, null, ex.getMessage(),
                        List.of(), version, request, credentials, List.of(), start, traceBase);
            }
            key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            if (conch.auth() != null && conch.auth().concurrentReads() && action.mcp().readOnly()) {
                // 只读能力先拿共享锁；会话不能直接用（没登录、要探活、中途失效）时放开，改走下面的独占锁。
                handoff = new Handoff();
                try {
                    locked = sessions.tryLockShared(key, deadline);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return internal(request, credentials, handoffRedaction(handoff, key), version, start, ex,
                            traceBase);
                }
                if (!locked) {
                    return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                            List.of(), version, request, credentials, handoffRedaction(handoff, key), start,
                            traceBase);
                }
                ExecutionResult result;
                try {
                    result = locked(request, conch, action, input, credentials, config, key, version, start,
                            deadline, handoff, true);
                } finally {
                    sessions.unlockShared(key);
                }
                if (result != null) {
                    return result;
                }
            }
            try {
                // 等锁时间算进能力总超时。
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, handoffRedaction(handoff, key), version, start, ex, traceBase);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, handoffRedaction(handoff, key), start, traceBase);
            }
            try {
                return locked(request, conch, action, input, credentials, config, key, version, start, deadline,
                        handoff, false);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, handoffRedaction(handoff, key), version, start, ex, traceBase);
        }
    }

    /**
     * 请求带着某个来源的账号和凭证进来。同名的本地包和正式包切换后，生效的可能已经是另一份；
     * 那时按「不存在」处理，不拿这个来源的凭证去跑另一个来源的规则。
     */
    private ConchDoc liveConch(ExecutionRequest request) {
        // 测试登录、接着登录也用在授权器账号上，所以这里不分是不是授权器；能力调用另有 actions 挡着。
        ConchRegistry.Active live = registry.installed(request.conch()).orElse(null);
        if (live == null) {
            return null;
        }
        if (live.origin() == request.origin()) {
            return live.doc();
        }
        // 正式来源的凭证不交给本地规则。反过来，本机没有本地那份时，
        // 本地来源的请求仍可以跑已验签的正式规则，与只有一个槽时的行为一致。
        boolean officialOnly = live.origin() == Origin.OFFICIAL && !registry.has(request.conch(), Origin.LOCAL);
        return officialOnly ? live.doc() : null;
    }

    private ConchDoc authorizedConch(ExecutionRequest request, ConchDoc authorized) {
        if (authorized == null) {
            return liveConch(request);
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
     * Local HTTP conches record a redacted developer trace while developer mode is enabled.
     */
    public ExecutionResult testLogin(ExecutionRequest request) {
        CallSecrets.begin();
        CallSite.begin(request);
        DevTraceScope.begin(openDraft(request, null));
        try {
            return testLoginCaptured(request);
        } finally {
            DevTraceScope.end();
            CallSite.end();
            CallSecrets.end();
        }
    }

    private ExecutionResult testLoginCaptured(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        URI traceBase = null;
        try {
            ConchDoc conch = liveConch(request);
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start, null);
            }
            traceBase = tryBaseUri(conch, config);
            version = conch.version().toString();
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, List.of(), version, start, ex, traceBase);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, List.of(), start, traceBase);
            }
            LoginScope scope = null;
            try {
                URI base = baseUri(conch, config);
                traceBase = base;
                scope = open(
                        conch, request.rawInput(), credentials, config, key, base, deadline);
                // 测试登录要验证的是这个账号自己的凭证：排队的成员可能在调用方清空之后又登录过，
                // 带着它的 Cookie 认证中心不会再要密码。拿到锁之后再清一次，从空白登录。
                // 业务包测的是换票：不清，罐里是授权器和其它包的会话。
                if (scope.bound == null) {
                    sessions.resetShared(key);
                }
                login(scope);
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start, traceBase);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                        credentials, redactionOf(scope, key), start, traceBase);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key), start, traceBase);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key), start, traceBase);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key), version, start, ex, traceBase);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex, traceBase);
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
            long deadline,
            Handoff handoff,
            boolean shared
    ) {
        LoginScope scope = null;
        URI traceBase = tryBaseUri(conch, config);
        try {
            if (shared && !sessions.sharedReady(key, conch.auth().aliveCheck() != null)) {
                return null;
            }
            if (sessions.loginPaused(key)) {
                return finish(false, null, ErrorCode.AUTH_FAILED, "该账号已暂停自动登录", null, null,
                        "该账号已暂停自动登录", List.of(), version, request, credentials,
                        redactionOf(null, key, handoff), start, traceBase);
            }
            URI base = baseUri(conch, config);
            traceBase = base;
            scope = open(conch, input, credentials, config, key, base, deadline);
            scope.shared = shared;
            if (shared && scope.bound != null && !linkedReady(scope)) {
                handoff.seen = scope.seenLogin;
                return null;
            }
            if (shared) {
                handoff.seen = scope.seenLogin;
            } else if (handoff != null && handoff.seen != null) {
                // 共享锁下那一次读到的登录值：快照可能已被清掉或换掉，它留下的步骤记录和追踪仍要打码。
                scope.seenLogin.addAll(handoff.seen);
            }
            boolean lostBefore = handoff != null && handoff.lost != null;
            if (shared) {
                handoff.epoch = sessions.epoch(key);
                requireStepLoginRefs(scope, action.steps());
                Attempt first = runAction(action, scope);
                if (first.lost) {
                    handoff.seen.addAll(sessions.redactionValues(key));
                    handoff.lost = first;
                    return null;
                }
                return settle(first, action, scope, key, version, request, credentials, start);
            }
            if (lostBefore) {
                // 共享锁下那一次已经发现会话失效：别的调用还没重新登录过才登出，这一次算重跑。
                if (sessions.markLoggedOutSince(key, handoff.epoch)) {
                    if (scope.bound != null) {
                        // 和独占锁下发现失效一样：记下要先探授权器，写了 auth.exchange 的连带删掉自己的 Cookie。
                        loseBound(scope);
                    }
                    noteLost(scope);
                }
                if (!action.idempotent()) {
                    return expired(handoff.lost.logs, version, request, credentials, scope, start);
                }
            }
            ensureSession(scope, !lostBefore);
            requireStepLoginRefs(scope, action.steps());
            Attempt attempt = runAction(action, scope);
            if (attempt.lost) {
                loseSession(scope);
                noteLost(scope);
                if (!action.idempotent() || lostBefore) {
                    return expired(attempt.logs, version, request, credentials, scope, start);
                }
                ensureSession(scope, false);
                requireStepLoginRefs(scope, action.steps());
                attempt = runAction(action, scope);
                if (attempt.lost) {
                    loseSession(scope);
                    noteLost(scope);
                    return expired(attempt.logs, version, request, credentials, scope, start);
                }
            }
            return settle(attempt, action, scope, key, version, request, credentials, start);
        } catch (NeedsExclusive ex) {
            if (handoff != null && handoff.seen != null) {
                handoff.seen.addAll(sessions.redactionValues(key));
            }
            return null;
        } catch (StepFailure ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                    credentials, redactionOf(scope, key, handoff), start, traceBase);
        } catch (DeadlineExceeded ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                    request, credentials, redactionOf(scope, key, handoff), start, traceBase);
        } catch (RuleProblem ex) {
            return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                    credentials, redactionOf(scope, key, handoff), start, traceBase);
        } catch (RuntimeException ex) {
            return internal(request, credentials, redactionOf(scope, key, handoff), version, start, ex, traceBase);
        }
    }

    /** 共享锁下那一次留给独占锁的话：当时的会话代次，以及发现会话失效的那次尝试。 */
    private static final class Handoff {
        private long epoch;
        private Attempt lost;
        private Set<String> seen;
    }

    /** 共享锁下需要登录：放开，改拿独占锁从头跑。只读能力才会走到这里。 */
    private static final class NeedsExclusive extends RuntimeException {
        private NeedsExclusive() {
            super(null, null, false, false);
        }
    }

    private ExecutionResult settle(
            Attempt attempt,
            Action action,
            LoginScope scope,
            SessionKey key,
            String version,
            ExecutionRequest request,
            Map<String, String> credentials,
            long start
    ) {
        try {
            if (attempt.failure != null) {
                StepFailure failure = attempt.failure;
                Integer index = failure.index < 0 ? null : failure.index;
                return finish(false, null, failure.code, failure.summary, index, failure.type, failure.summary,
                        failure.logs, version, request, credentials, scope.redactionValues(), start, scope.base);
            }
            Object data;
            try {
                data = templates.render(action.outputTemplate(), attempt.vars);
            } catch (TemplateException ex) {
                String text = undefinedOr(ex);
                return finish(false, null, ErrorCode.RULE_MISMATCH, text, null, null, text,
                        attempt.logs, version, request, credentials, scope.redactionValues(), start, scope.base);
            }
            sessions.touch(key);
            return finish(true, data, null, null, null, null, null, attempt.logs, version, request, credentials,
                    scope.redactionValues(), start, scope.base);
        } catch (StepFailure ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version, request,
                    credentials, redactionOf(scope, key), start, scope.base);
        } catch (DeadlineExceeded ex) {
            Integer index = ex.index < 0 ? null : ex.index;
            return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                    request, credentials, redactionOf(scope, key), start, scope.base);
        } catch (RuleProblem ex) {
            return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                    credentials, redactionOf(scope, key), start, scope.base);
        } catch (RuntimeException ex) {
            return internal(request, credentials, redactionOf(scope, key), version, start, ex, scope.base);
        }
    }

    /** 当前会话名单，加上这次调用已经见过的登录值。scope 还没建好时合并共享锁阶段见过的登录值。 */
    private List<String> redactionOf(LoginScope scope, SessionKey key) {
        return redactionOf(scope, key, (Handoff) null);
    }

    private List<String> redactionOf(LoginScope scope, SessionKey key, Handoff handoff) {
        if (scope != null) {
            return scope.redactionValues();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (handoff != null && handoff.seen != null) {
            values.addAll(handoff.seen);
        }
        values.addAll(sessions.redactionValues(key));
        return List.copyOf(values);
    }

    /** 独占锁未拿到或外层异常时，保留共享锁阶段见过的登录值和当前会话名单；不因等写锁而阻塞。 */
    private List<String> handoffRedaction(Handoff handoff, SessionKey key) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (handoff != null && handoff.seen != null) {
            values.addAll(handoff.seen);
        }
        if (key != null) {
            try {
                values.addAll(sessions.redactionValuesNow(key));
            } catch (RuntimeException ignored) {
                // 会话管理器已关闭等情况不影响返回错误，至少保留已见过的登录值。
            }
        }
        return List.copyOf(values);
    }

    /**
     * 会话失效。绑了身份的账号先不清共用的 Cookie，带着它们重新登录；
     * 这次调用里已经这样登录过，留着的 Cookie 可能没用，清空整个身份。
     * 同一个成员短时间内只清一次：规则把业务错误当成失效时清空没有用，不让它反复登出同身份的成员。
     */
    private void loseSession(LoginScope scope) {
        if (scope.bound != null) {
            loseBound(scope);
            return;
        }
        if (scope.reusedCookies) {
            scope.reusedCookies = false;
            sessions.clearSharedAfterRelapse(scope.key);
            return;
        }
        sessions.markLoggedOut(scope.key);
    }

    private void ensureSession(LoginScope scope, boolean allowAdopt) {
        if (scope.bound != null) {
            ensureBound(scope);
            return;
        }
        Auth auth = scope.conch.auth();
        AliveCheck alive = auth == null ? null : auth.aliveCheck();
        boolean loggedIn = sessions.loggedIn(scope.key);
        // 身份里别的成员已经登录过：写了 alive_check 的包先探活，通过就直接用，不再登录。
        // 这次调用刚发现会话失效要重登时不再走探活接手，直接跑登录步骤。
        boolean adopting = allowAdopt && !loggedIn && alive != null && sessions.sharesCookies(scope.key)
                && sessions.pending().current(scope.key) == null;
        if (!loggedIn && !adopting) {
            login(scope);
            return;
        }
        if (!adopting && (!sessions.idle(scope.key) || alive == null)) {
            return;
        }
        requireLoginRefs(scope, alive.request());
        if (adopting && sessions.loggedIn(scope.key)) {
            // alive_check 引用了登录变量，requireLoginRefs 已经触发了完整登录，不再补跑一次探活。
            return;
        }
        if (!probe(scope)) {
            if (!adopting) {
                sessions.markLoggedOut(scope.key);
            }
            if (auth == null || auth.steps().isEmpty()) {
                throw new StepFailure(
                        ErrorCode.SESSION_EXPIRED,
                        "会话已失效，且规则未配置登录步骤",
                        -1,
                        "request",
                        List.of());
            }
            login(scope);
            return;
        }
        if (adopting) {
            sessions.markLoggedIn(scope.key);
        }
        sessions.touch(scope.key);
    }

    /** 跑一次 alive_check。调用方已经把它引用的登录变量准备好。返回会话是否还在。 */
    private boolean probe(LoginScope scope) {
        Auth auth = scope.conch.auth();
        AliveCheck alive = auth.aliveCheck();
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
                    HttpCalls.sensitiveValues(vars, sessions.cookies(scope.key),
                            sessions.seenCookieValues(scope.key)),
                    settings.bodyIdleTimeout(),
                    scope.reach(false));
        } catch (RuleProblem ex) {
            recordAlive(spec, ex.method, ex.path, ex.status, ex.responseHeaders, ex.responseBody, ex.wire, started,
                    false, ex.transport, ex.warnings);
            throw ex;
        }
        List<TraceWarning> aliveWarnings = new ArrayList<>(result.warnings());
        boolean aliveOk = !(result.sessionLost() || result.status() == 401 || result.status() == 403);
        try {
            if (aliveOk && auth != null && !auth.expiredWhen().isEmpty()) {
                int matched = SessionExpiry.matchExpired(
                        auth.expiredWhen(), result, sessions.cookies(scope.key), scope.base, scope.deadline);
                if (matched >= 0) {
                    aliveOk = false;
                    aliveWarnings.add(new TraceWarning(
                            TraceWarning.SESSION_EXPIRED,
                            "命中 auth.expired_when 第 " + (matched + 1) + " 条规则，按会话失效处理"));
                }
            }
            if (aliveOk && !alive.expect().isEmpty()) {
                String failed = SessionExpiry.firstUnmetExpect(
                        alive.expect(), result, sessions.cookies(scope.key), scope.base, scope.deadline);
                if (failed != null) {
                    aliveOk = false;
                    aliveWarnings.add(new TraceWarning(
                            TraceWarning.SESSION_EXPIRED,
                            "未通过 alive_check.expect（" + failed + "），按会话失效处理"));
                }
            }
        } catch (RuleProblem | DeadlineExceeded ex) {
            recordAlive(spec, result.method(), result.path(), result.status(), result.headers(), result.body(),
                    result.wire(), started, false, null, aliveWarnings);
            throw ex;
        }
        recordAlive(spec, result.method(), result.path(), result.status(), result.headers(), result.body(),
                result.wire(), started, aliveOk, null, aliveWarnings);
        scope.probed = aliveOk;
        return aliveOk;
    }

    /**
     * 业务包：先保证授权器账号登录着，再保证这个包自己换过票。两者共用一把账号锁，调用方已经持有。
     * 这个包上一次被判为会话失效时，先探授权器：授权器的会话没了才重新登录它；还在的话，写了
     * {@code auth.exchange} 的包只重新换票，没写的包（直接用导出值）在限流之内让授权器重新登录一次。
     * 换票失败只算这个包自己的，不记到授权器账号上。
     */
    private void ensureBound(LoginScope scope) {
        LoginScope a = scope.bound;
        Auth auth = scope.conch.auth();
        try {
            if (sessions.loginPaused(a.key)) {
                throw new StepFailure(ErrorCode.AUTH_FAILED, LINK_PAUSED, -1, "request", List.of());
            }
            ensureSession(a, true);
            if (sessions.boundEpoch(scope.key) == LINK_LOST && !a.loggedInThisCall) {
                requireLoginRefs(a, a.conch.auth().aliveCheck().request());
                boolean alive = !a.loggedInThisCall && (a.probed || probe(a));
                if (!a.loggedInThisCall) {
                    if (!alive) {
                        sessions.markLoggedOut(a.key);
                        login(a);
                    } else {
                        sessions.touch(a.key);
                        if (auth.exchange().isEmpty() && sessions.allowBoundRelogin(scope.key, a.key)) {
                            sessions.markLoggedOut(a.key);
                            login(a);
                        }
                    }
                }
            }
            // 授权器更新后多导出了变量，而已有的登录是旧版本做的：在限流之内重新登录一次补上，
            // 不让这个包一直报「登录没有建立变量」。
            if (!a.loggedInThisCall && missingExport(scope, a) && sessions.allowBoundRelogin(scope.key, a.key)) {
                sessions.markLoggedOut(a.key);
                login(a);
            }
        } catch (StepFailure ex) {
            if (ex.code == ErrorCode.LOGIN_INPUT_REQUIRED) {
                // 等的是授权器账号的验证码；对外只说这个包，授权器的包名不出现在调用结果里。
                throw new StepFailure(ex.code, LoginInputMessages.required(scope.conch.conch()), ex.index, ex.type,
                        ex.logs);
            }
            throw ex;
        }
        long epoch = sessions.epoch(a.key);
        if (sessions.loggedIn(scope.key) && sessions.boundEpoch(scope.key) == epoch) {
            AliveCheck alive = auth.aliveCheck();
            if (alive == null || !sessions.idle(scope.key)) {
                return;
            }
            requireLoginRefs(scope, alive.request());
            if (probe(scope)) {
                sessions.touch(scope.key);
                return;
            }
            // 这个包自己的探活没过，和能力被判为失效一样处理：先看授权器，再重新换票。
            loseBound(scope);
            ensureBound(scope);
            return;
        }
        exchange(scope);
    }

    /** 这个包用到的导出变量里，有没有授权器现在的登录里拿不到的。 */
    private boolean missingExport(LoginScope scope, LoginScope authenticator) {
        Map<String, AuthExport> declared = authenticator.conch.auth().exports();
        for (String name : AuthenticatorChecks.referencedExports(scope.conch)) {
            if (declared.containsKey(name) && authenticator.lookup(name) == null) {
                return true;
            }
        }
        return false;
    }

    /** 这个包自己的换票状态作废。写了 auth.exchange 的包连带删掉只属于它站点的 Cookie；授权器的会话不动。 */
    private void dropBound(LoginScope scope) {
        if (scope.conch.auth().exchange().isEmpty()) {
            sessions.markLoggedOut(scope.key);
        } else {
            // 认证地址和别的业务包所在的主机上，只删路径落在自己 base_url 下的 Cookie。
            Set<String> sharedHosts = scope.bound.hosts();
            for (String site : scope.siblingSites) {
                try {
                    String host = URI.create(site).getHost();
                    if (host != null) {
                        sharedHosts.add(host.toLowerCase(java.util.Locale.ROOT));
                    }
                } catch (IllegalArgumentException ex) {
                    // 不是地址，跳过
                }
            }
            sessions.dropOwnCookies(scope.key, scope.base, sharedHosts);
        }
    }

    private void loseBound(LoginScope scope) {
        dropBound(scope);
        sessions.boundEpoch(scope.key, LINK_LOST);
        // 同一次调用里重跑时，授权器要重新探一次；这次调用里刚登录过的不用。
        scope.bound.probed = false;
    }

    /** 用授权器的会话给这个包换票。没写 auth.exchange 时只记下这一代授权器会话。 */
    private void exchange(LoginScope scope) {
        Auth auth = scope.conch.auth();
        long epoch = sessions.epoch(scope.bound.key);
        scope.loggedInThisCall = true;
        if (auth.exchange().isEmpty()) {
            scope.replaceSnapshot(Map.of());
            sessions.markLoggedIn(scope.key);
            sessions.boundEpoch(scope.key, epoch);
            sessions.recordAuthSuccess(scope.key);
            return;
        }
        scope.loggingIn = true;
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            draft.login(true, null);
        }
        ArrayList<StepLog> logs = new ArrayList<>();
        Map<String, Object> vars = baseVars(scope);
        try {
            runSteps(auth.exchange(), vars, true, null, logs, scope.key, scope.base, scope.reach(true),
                    scope.deadline, "login", scope.conch.scripts(), 0, false, scope.conch);
            Map<String, String> snapshot;
            try {
                snapshot = LoginSnapshots.capture(auth.exchange(), vars, new ArrayList<>());
                LoginSnapshots.checkSize(snapshot, auth.exchange().size() - 1);
            } catch (StepFailure ex) {
                throw new StepFailure(ex.code, ex.summary, ex.index, ex.type, logs);
            }
            scope.replaceSnapshot(snapshot);
            sessions.markLoggedIn(scope.key);
            sessions.boundEpoch(scope.key, epoch);
            sessions.recordAuthSuccess(scope.key);
            noteLogin(scope);
            if (draft != null) {
                draft.login(true, true);
            }
        } catch (StepFailure ex) {
            if (draft != null) {
                draft.login(true, false);
            }
            if (ex.code == ErrorCode.AUTH_FAILED) {
                sessions.recordAuthFailure(scope.key);
            }
            throw ex;
        } catch (SessionLost ex) {
            if (draft != null) {
                draft.login(true, false);
            }
            sessions.recordAuthFailure(scope.key);
            throw new StepFailure(ErrorCode.AUTH_FAILED, "登录失败", -1, "request", logs);
        } catch (RuntimeException ex) {
            if (draft != null) {
                draft.login(true, false);
            }
            throw ex;
        } finally {
            scope.loggingIn = false;
        }
    }

    /** 只持有共享锁时：授权器登录着、不用探活，这个包换的票也还是这一代授权器会话的，才能直接跑。 */
    private boolean linkedReady(LoginScope scope) {
        SessionKey a = scope.bound.key;
        return sessions.sharedReady(a, true) && sessions.boundEpoch(scope.key) == sessions.epoch(a);
    }

    /** 建这次调用的作用域。业务包在这里接上授权器账号；没有可用的绑定时报 NOT_CONFIGURED。 */
    private LoginScope open(
            ConchDoc conch,
            Map<String, Object> input,
            Map<String, String> credentials,
            Map<String, String> config,
            SessionKey key,
            URI base,
            long deadline
    ) {
        LoginScope scope = new LoginScope(conch, input, credentials, config, key, base, deadline);
        if (conch.auth() == null || conch.auth().use() == null) {
            return scope;
        }
        AuthBindings.BoundAuth boundAuth = authBindings.resolve(key, conch, SiteAddress.effective(conch, config.get("base_url")));
        if (boundAuth == null || !AuthenticatorChecks.isAuthenticator(boundAuth.authenticator())
                || !sessions.join(key, boundAuth.key())) {
            throw new RuleProblem(ErrorCode.NOT_CONFIGURED, LINK_MISSING, null, null, null);
        }
        Map<String, String> authConfig = copyStrings(boundAuth.config());
        URI authBase;
        try {
            authBase = baseUri(boundAuth.authenticator(), authConfig);
        } catch (RuleProblem ex) {
            throw new RuleProblem(ErrorCode.NOT_CONFIGURED, LINK_MISSING, null, null, null);
        }
        LoginScope a = new LoginScope(boundAuth.authenticator(), Map.of(), copyStrings(boundAuth.credentials()), authConfig,
                boundAuth.key(), authBase, deadline);
        scope.bound = a;
        scope.siblingSites = boundAuth.siblingSites();
        scope.authOrigins = a.authOrigins;
        return scope;
    }

    /**
     * 用户在控制台提交验证码后，从 {@code ask} 的下一步接着登录。不使会话失效，Cookie 还在。
     * 失败丢掉待输入，并且不记入自动登录暂停。
     */
    public ExecutionResult continueLogin(ExecutionRequest request, String value) {
        CallSecrets.begin();
        CallSite.begin(request);
        DevTraceScope.begin(openDraft(request, null));
        try {
            return continueCaptured(request, value);
        } finally {
            DevTraceScope.end();
            CallSite.end();
            CallSecrets.end();
        }
    }

    /** 重跑 {@code ask} 前一个 request。图片验证码换图，短信或口令则重新发送。 */
    public ExecutionResult resendLogin(ExecutionRequest request) {
        CallSecrets.begin();
        CallSite.begin(request);
        DevTraceScope.begin(openDraft(request, null));
        try {
            return resendCaptured(request);
        } finally {
            DevTraceScope.end();
            CallSite.end();
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
        URI traceBase = null;
        try {
            ConchDoc conch = liveConch(request);
            traceBase = tryBaseUri(conch, config);
            version = conch == null ? null : conch.version().toString();
            if (rejected != null) {
                return finish(false, null, ErrorCode.INVALID_INPUT, rejected, null, null, rejected, List.of(),
                        version, request, credentials, supplied, start, traceBase);
            }
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, supplied, start, null);
            }
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, supplied, version, start, ex, traceBase);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, supplied, start, traceBase);
            }
            LoginScope scope = null;
            try {
                PendingLogins.Pending pending = sessions.pending().current(key);
                if (pending == null) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, List.of(), version, request, credentials, supplied, start,
                            traceBase);
                }
                if (!samePackage(conch, pending)) {
                    sessions.pending().discard(key);
                    return finish(false, null, ErrorCode.CONFLICT, LoginInputMessages.UPDATED, null, null,
                            LoginInputMessages.UPDATED, List.of(), version, request, credentials, supplied, start,
                            traceBase);
                }
                sessions.pending().discard(key);
                URI base = baseUri(conch, config);
                traceBase = base;
                scope = open(conch, request.rawInput(), credentials, config, key, base, deadline);
                scope.remember(value);
                login(scope, pending, value, false);
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start, traceBase);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version,
                        request, credentials, redactionOf(scope, key, supplied), start, traceBase);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key, supplied), start, traceBase);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key, supplied), start, traceBase);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key, supplied), version, start, ex, traceBase);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, supplied, version, start, ex, traceBase);
        }
    }

    private ExecutionResult resendCaptured(ExecutionRequest request) {
        long start = System.nanoTime();
        long deadline = Math.addExact(start, settings.capabilityTimeout().toNanos());
        Map<String, String> credentials = copyStrings(request.accountContext().credentials());
        Map<String, String> config = copyStrings(request.accountContext().config());
        String version = null;
        URI traceBase = null;
        try {
            ConchDoc conch = liveConch(request);
            traceBase = tryBaseUri(conch, config);
            version = conch == null ? null : conch.version().toString();
            if (conch == null) {
                return finish(false, null, ErrorCode.NOT_FOUND, "规则包不存在", null, null, "规则包不存在",
                        List.of(), version, request, credentials, List.of(), start, null);
            }
            SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
            boolean locked;
            try {
                locked = sessions.tryLock(key, deadline);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return internal(request, credentials, List.of(), version, start, ex, traceBase);
            }
            if (!locked) {
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", null, null, "执行超时",
                        List.of(), version, request, credentials, List.of(), start, traceBase);
            }
            LoginScope scope = null;
            try {
                PendingLogins.Pending pending = sessions.pending().current(key);
                if (pending == null) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, List.of(), version, request, credentials, List.of(), start,
                            traceBase);
                }
                if (!samePackage(conch, pending)) {
                    sessions.pending().discard(key);
                    return finish(false, null, ErrorCode.CONFLICT, LoginInputMessages.UPDATED, null, null,
                            LoginInputMessages.UPDATED, List.of(), version, request, credentials, List.of(), start,
                            traceBase);
                }
                Auth auth = conch.auth();
                PendingLogins.ResendDecision decision = sessions.pending().beginResend(key);
                if (!decision.accepted()) {
                    return finish(false, null, decision.code(), decision.message(), null, null, decision.message(),
                            List.of(), version, request, credentials, List.of(), start, traceBase);
                }
                PendingLogins.Pending ticket = decision.pending();
                int stepIndex = ticket.askIndex() - 1;
                if (auth == null || stepIndex < 0 || stepIndex >= auth.steps().size()) {
                    return finish(false, null, ErrorCode.AUTH_FAILED, LoginInputMessages.NO_RESEND, null, null,
                            LoginInputMessages.NO_RESEND, List.of(), version, request, credentials, List.of(), start,
                            traceBase);
                }
                URI base = baseUri(conch, config);
                traceBase = base;
                scope = open(conch, request.rawInput(), credentials, config, key, base, deadline);
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
                        scope.reach(true),
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
                        traceAsk(ticket.askIndex(), 0, ticket.kind(), ticket.label(), reason);
                        return finish(false, null, ErrorCode.AUTH_FAILED, reason, stepIndex, "request", reason, logs,
                                version, request, credentials, scope.redactionValues(), start, traceBase);
                    }
                    CaptchaImages.Accepted accepted = CaptchaImages.accept(bytes);
                    if (!sessions.pending().completeResend(key, ticket.id(), accepted.bytes(), accepted.mediaType())) {
                        return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                                LoginInputMessages.NONE, logs, version, request, credentials, scope.redactionValues(),
                                start, traceBase);
                    }
                } else if (!sessions.pending().completeResend(key, ticket.id(), null, null)) {
                    return finish(false, null, ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null, null,
                            LoginInputMessages.NONE, logs, version, request, credentials, scope.redactionValues(),
                            start, traceBase);
                }
                return finish(true, Map.of("ok", true), null, null, null, null, null, List.of(), version, request,
                        credentials, scope.redactionValues(), start, traceBase);
            } catch (StepFailure ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ex.code, ex.summary, index, ex.type, ex.summary, ex.logs, version,
                        request, credentials, redactionOf(scope, key, List.of()), start, traceBase);
            } catch (DeadlineExceeded ex) {
                Integer index = ex.index < 0 ? null : ex.index;
                return finish(false, null, ErrorCode.TIMEOUT, "执行超时", index, ex.type, "执行超时", ex.logs, version,
                        request, credentials, redactionOf(scope, key, List.of()), start, traceBase);
            } catch (RuleProblem ex) {
                return finish(false, null, ex.code, ex.summary, null, null, ex.summary, List.of(), version, request,
                        credentials, redactionOf(scope, key, List.of()), start, traceBase);
            } catch (RuntimeException ex) {
                return internal(request, credentials, redactionOf(scope, key, List.of()), version, start, ex,
                        traceBase);
            } finally {
                sessions.unlock(key);
            }
        } catch (RuntimeException ex) {
            return internal(request, credentials, List.of(), version, start, ex, traceBase);
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
                traceAsk(at, took, kind, label, reason);
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
        traceAsk(index, took, kind, label, null);
    }

    /**
     * {@code failure} 非空时是图片读不出来。写入 {@code passed: false} 和现有句子，不放图片字节。
     * 停下来等输入时没有这句，不能把等待标成未通过。
     */
    private static void traceAsk(int index, long took, String kind, String label, String failure) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null) {
            return;
        }
        LinkedHashMap<String, Object> piece = new LinkedHashMap<>();
        piece.put("type", "ask");
        piece.put("kind", kind);
        piece.put("label", label);
        if (failure != null) {
            piece.put("passed", Boolean.FALSE);
            if (!failure.isBlank()) {
                piece.put("error", failure);
            }
        }
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
        if (scope.bound != null) {
            // 这个包自己没有登录步骤：登录就是重新换票（授权器该不该重新登录由 ensureBound 判断）。
            // 写了 auth.exchange 的连带删掉只属于它站点的 Cookie，从没有应用会话的状态换起。
            dropBound(scope);
            ensureBound(scope);
            return;
        }
        Auth auth = scope.conch.auth();
        if (auth == null || auth.steps().isEmpty() || !sessions.sharesCookies(scope.key)) {
            login(scope, null, null, true);
            return;
        }
        // 共用的罐里有 Cookie：和浏览器一样带着它们登录，认证中心的登录还有效时不用再交密码。
        // 这样登录失败不算数：清空这个身份的 Cookie，从空白再登录一次，那一次失败才记登录失败。
        scope.reusedCookies = true;
        try {
            login(scope, null, null, false);
            return;
        } catch (StepFailure ex) {
            if (!retriesFromScratch(ex) || sessions.pending().current(scope.key) != null) {
                throw ex;
            }
        }
        scope.reusedCookies = false;
        sessions.clearShared(scope.key);
        DevTraceDraft draft = DevTraceScope.current();
        if (draft != null) {
            draft.addWarning("login", -1, "request", new TraceWarning(
                    TraceWarning.SHARED_SESSION_CLEARED, "带着身份里已有的 Cookie 登录没有成功，已清空这个身份的 Cookie 重新登录。"));
        }
        login(scope, null, null, true);
    }

    /**
     * 超时、连不上、上游 5xx 或等待输入不是「留着的 Cookie 没用」，不清空整个身份的 Cookie 重来。
     */
    private static boolean retriesFromScratch(StepFailure failure) {
        return failure.code == ErrorCode.AUTH_FAILED || failure.code == ErrorCode.RULE_MISMATCH;
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
                runSteps(steps, vars, true, null, logs, scope.key, scope.base, scope.reach(true), scope.deadline,
                        "login",
                        scope.conch.scripts(), indexBase, false, scope.conch);
                Map<String, String> snapshot;
                ArrayList<LoginSnapshots.Skipped> skipped = new ArrayList<>();
                try {
                    snapshot = LoginSnapshots.capture(auth.steps(), vars, skipped);
                    int at = auth.steps().isEmpty() ? -1 : auth.steps().size() - 1;
                    LoginSnapshots.checkSize(snapshot, at);
                } catch (StepFailure ex) {
                    throw new StepFailure(ex.code, ex.summary, ex.index, ex.type, logs);
                }
                if (draft != null) {
                    for (LoginSnapshots.Skipped skip : skipped) {
                        draft.addWarning("login", skip.index(), skip.type(), new TraceWarning(
                                TraceWarning.SNAPSHOT_SKIPPED, LoginSnapshots.skippedMessage(skip.name())));
                    }
                }
                scope.replaceSnapshot(snapshot);
                sessions.markLoggedIn(scope.key);
                noteLogin(scope);
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
        FileScope fileScope = FileScope.current();
        if (fileScope != null) {
            fileScope.beginAttempt();
        }
        boolean committed = false;
        try {
            runSteps(action.steps(), vars, false, expectPrefix(scope.conch), logs, scope.key, scope.base,
                    scope.reach(false), scope.deadline, "action", scope.conch.scripts(), 0, false, scope.conch);
            if (fileScope != null) {
                fileScope.commitAttempt();
                committed = true;
            }
            return Attempt.ok(vars, logs);
        } catch (SessionLost ex) {
            return Attempt.lost(logs);
        } catch (StepFailure ex) {
            return Attempt.failed(ex);
        } finally {
            if (fileScope != null && !committed) {
                fileScope.rollbackAttempt();
            }
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
            AuthReach reach,
            long deadline,
            String phase,
            Map<String, String> libraries
    ) {
        return runSteps(definitions, vars, auth, expectPrefix, logs, key, base, reach, deadline, phase, libraries,
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
            AuthReach reach,
            long deadline,
            String phase,
            Map<String, String> libraries,
            int indexBase,
            boolean hideResponseBody,
            ConchDoc conch
    ) {
        Block block = new Block();
        if (vars.get(ConchRegistry.AUTH_VAR) instanceof HttpCalls.SecretSource) {
            // 授权器导出的值和 login.* 一样：引用它的 set / extract 登记秘密，不流进普通 script。
            block.taintedVars.add(ConchRegistry.AUTH_VAR);
        }
        return runBlock(definitions, vars, auth, expectPrefix, logs, key, base, reach, deadline, phase, libraries,
                indexBase, hideResponseBody, conch, block, -1, 0, null);
    }

    /** 一次 runSteps 里跨 if / repeat 子步骤带着走的状态。 */
    private static final class Block {
        boolean deriveNext;
        byte[] previousBytes;
        boolean previousRequest;
        Outcome last;
        /** 大于 0 时正在 repeat 的第几轮，写进追踪。 */
        int round;
        /** 能力步骤里从 login.* 派生出的变量名；禁止流入未声明 credential: true 的 script。 */
        final Set<String> taintedVars = new LinkedHashSet<>();
    }

    /**
     * 当前步骤块跑完后紧接着会执行的外层上下文：可能是外层 repeat 的 until 条件，
     * 以及外层步骤列表里剩下的步骤，用于判断非 2xx 响应是否已被规则显式接住。
     */
    private record Continuation(
            List<Step> steps,
            int from,
            Map<String, Object> until,
            boolean hasNextRound,
            Continuation parent
    ) {
    }

    /**
     * {@code fixedIndex} 不小于 0 时是 if / repeat 的子步骤：日志、追踪和报错都记在外层那一步的下标上。
     */
    private Outcome runBlock(
            List<Step> definitions,
            Map<String, Object> vars,
            boolean auth,
            String expectPrefix,
            List<StepLog> logs,
            SessionKey key,
            URI base,
            AuthReach reach,
            long deadline,
            String phase,
            Map<String, String> libraries,
            int indexBase,
            boolean hideResponseBody,
            ConchDoc conch,
            Block block,
            int fixedIndex,
            int depth,
            Continuation continuation
    ) {
        CloseableHttpClient client = sessions.client(key);
        Runnable persist = () -> sessions.persist(key);
        DevTraceDraft draft = DevTraceScope.current();
        boolean capture = draft != null;
        boolean loginPhase = "login".equals(phase);
        try {
        for (int i = 0; i < definitions.size(); i++) {
            Step step = definitions.get(i);
            int at = fixedIndex >= 0 ? fixedIndex : indexBase + i;
            if (draft != null) {
                boolean extractOrScript = "extract".equals(step.type()) || "script".equals(step.type());
                draft.collectDerived(loginPhase || (block.deriveNext && extractOrScript), !loginPhase);
            }
            if (System.nanoTime() >= deadline) {
                throw new DeadlineExceeded(at, step.type(), logs);
            }
            long started = System.nanoTime();
            if ("ask".equals(step.type())) {
                if (fixedIndex >= 0) {
                    throw new StepFailure(
                            ErrorCode.RULE_MISMATCH, "ask 不能写在 if 或 repeat 里", at, "ask", logs);
                }
                throw pauseForAsk(step, at, vars, block.previousRequest, block.previousBytes, logs, started,
                        conch, key, auth);
            }
            DevTraceScope.takePending();
            try {
                if (FlowSteps.isFlow(step)) {
                    if (depth >= FlowSteps.MAX_DEPTH) {
                        throw new RuleProblem(ErrorCode.RULE_MISMATCH,
                                "if / repeat 最多嵌套 " + FlowSteps.MAX_DEPTH + " 层", null, null, null);
                    }
                    // 调用记录按步骤下标存，一步只能有一条：子步骤记在另一张表里，外层只留一条。
                    List<StepLog> inner = new ArrayList<>();
                    Continuation afterFlow = new Continuation(definitions, i + 1, null, false, continuation);
                    try {
                        runFlow(step, at, started, vars, auth, expectPrefix, inner, key, base, reach, deadline, phase,
                                libraries, hideResponseBody, conch, block, depth, afterFlow);
                    } catch (StepFailure ex) {
                        StepLog failed = ex.logs.isEmpty() ? null : ex.logs.get(ex.logs.size() - 1);
                        logs.add(failed == null
                                ? new StepLog(at, step.type(), elapsed(started), null, null, null, null)
                                : new StepLog(at, failed.type(), elapsed(started), failed.method(), failed.path(),
                                        failed.status(), failed.extractCount()));
                        throw new StepFailure(ex.code, ex.summary, at, ex.type, logs);
                    } catch (SessionLost ex) {
                        StepLog failed = inner.isEmpty() ? null : inner.get(inner.size() - 1);
                        logs.add(failed == null
                                ? new StepLog(at, step.type(), elapsed(started), null, null, null, null)
                                : new StepLog(at, failed.type(), elapsed(started), failed.method(), failed.path(),
                                        failed.status(), failed.extractCount()));
                        throw ex;
                    }
                    logs.add(new StepLog(at, step.type(), elapsed(started), null, null, null, null));
                    continue;
                }
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
                        libraries,
                        reach,
                        auth ? List.of() : expiredWhenOf(conch),
                        auth,
                        sessions.seenCookieValues(key),
                        block.taintedVars);
                block.last = outcome;
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
                    if (block.round > 0) {
                        outcome.capture().put("round", block.round);
                    }
                    draft.add(phase, at, elapsed(started), outcome.capture());
                }
                if (!loginPhase && "request".equals(step.type())) {
                    block.deriveNext = templates.referencesCredential(step.def());
                }
                block.previousRequest = "request".equals(step.type());
                block.previousBytes = block.previousRequest ? outcome.bodyBytes() : null;
                // 只打断能力请求。登录步骤仍走 assert，失败保持 AUTH_FAILED。
                // 下标用这一段里的位置：能力步骤的 indexBase 是 0，登录续跑不会进这里。
                rejectCapabilityStatus(auth, outcome, i, at, step.type(), logs, definitions, continuation);
            } catch (StepFailure | SessionLost ex) {
                // 子步骤已经记过日志和追踪。
                throw ex;
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
                recordFailure(
                        phase, at, step, elapsed(started), ex, summary, credentialBearingRequest(loginPhase, step));
                throw new StepFailure(code, summary, at, step.type(), logs);
            } catch (TemplateException ex) {
                String summary = undefinedOr(ex);
                logs.add(new StepLog(at, step.type(), elapsed(started), null, null, null, null));
                recordFailure(
                        phase, at, step, elapsed(started), null, summary, credentialBearingRequest(loginPhase, step));
                ErrorCode code = auth ? ErrorCode.AUTH_FAILED : ErrorCode.RULE_MISMATCH;
                throw new StepFailure(code, summary, at, step.type(), logs);
            } catch (DeadlineExceeded ex) {
                // 子步骤超时的，类型已经是那个子步骤的。
                throw new DeadlineExceeded(at, ex.index >= 0 ? ex.type : step.type(), logs);
            }
        }
        } finally {
            if (draft != null && fixedIndex < 0) {
                draft.collectDerived(false);
            }
        }
        return block.last;
    }

    /** if：判断后跑 then 或 else。repeat：最多 max 轮，每轮跑 steps、汇总 collect、判断 until。 */
    private void runFlow(
            Step step,
            int at,
            long started,
            Map<String, Object> vars,
            boolean auth,
            String expectPrefix,
            List<StepLog> logs,
            SessionKey key,
            URI base,
            AuthReach reach,
            long deadline,
            String phase,
            Map<String, String> libraries,
            boolean hideResponseBody,
            ConchDoc conch,
            Block block,
            int depth,
            Continuation afterFlow
    ) {
        DevTraceDraft draft = DevTraceScope.current();
        if (FlowSteps.IF.equals(step.type())) {
            Map<String, Object> condition = Values.map(templates.render(FlowSteps.condition(step), vars));
            boolean holds = steps.test(condition, vars, sessions.cookies(key), base);
            String branch = holds ? "then" : "else";
            List<Step> chosen = FlowSteps.branch(step, branch);
            if (draft != null) {
                LinkedHashMap<String, Object> piece = new LinkedHashMap<>();
                piece.put("type", "if");
                piece.put("conditions", List.copyOf(condition.keySet()));
                piece.put("result", holds);
                piece.put("branch", chosen.isEmpty() ? "none" : branch);
                if (block.round > 0) {
                    piece.put("round", block.round);
                }
                draft.add(phase, at, elapsed(started), piece);
            }
            runBlock(chosen, vars, auth, expectPrefix, logs, key, base, reach, deadline, phase, libraries,
                    0, hideResponseBody, conch, block, at, depth + 1, afterFlow);
            return;
        }
        if (block.round > 0) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, "repeat 里不能再写 repeat", null, null, null);
        }
        int max = Math.max(1, Math.min(FlowSteps.REPEAT_MAX, Values.integer(step.def().get("max"), 1)));
        List<Step> body = FlowSteps.branch(step, "steps");
        Map<String, Object> collect = Values.map(step.def().get("collect"));
        Map<String, Object> until = Values.map(step.def().get("until"));
        LinkedHashMap<String, List<Object>> gathered = new LinkedHashMap<>();
        if (collect != null) {
            for (String target : collect.keySet()) {
                gathered.put(target, new ArrayList<>());
            }
        }
        Map<String, Object> taken = new HashMap<>();
        int rounds = 0;
        boolean stopped = false;
        try {
            while (rounds < max && !stopped) {
                if (System.nanoTime() >= deadline) {
                    throw new DeadlineExceeded(at, "repeat", logs);
                }
                rounds++;
                block.round = rounds;
                vars.put(FlowSteps.LOOP, Map.of("index", rounds));
                boolean lastRound = rounds >= max;
                Continuation repeatNext = until == null
                        ? (lastRound ? afterFlow : new Continuation(body, 0, null, false, null))
                        : new Continuation(afterFlow.steps(), afterFlow.from(), until, !lastRound, afterFlow.parent());
                runBlock(body, vars, auth, expectPrefix, logs, key, base, reach, deadline, phase, libraries,
                        0, hideResponseBody, conch, block, at, depth + 1, repeatNext);
                if (collect != null) {
                    for (Map.Entry<String, Object> entry : collect.entrySet()) {
                        String sourcePath = Values.text(entry.getValue());
                        Object value = StepRunner.lookup(vars, sourcePath);
                        // 还是上一轮接过的那个列表：这一轮没有重新写它，不再接一遍。
                        if (value != null && value == taken.put(entry.getKey(), value)) {
                            continue;
                        }
                        if (value instanceof List<?> list) {
                            gathered.get(entry.getKey()).addAll(list);
                            if (StepRunner.tracksSecrets(auth, vars) && StepRunner.referencesAny(sourcePath, block.taintedVars)) {
                                CallSecrets.remember(list);
                                block.taintedVars.add(entry.getKey());
                            }
                        } else if (value != null) {
                            throw new RuleProblem(ErrorCode.RULE_MISMATCH,
                                    "collect 的 " + entry.getValue() + " 不是列表。"
                                            + "列表要用带 each 的 extract 或返回数组的 jsonpath 得到",
                                    null, null, null);
                        }
                    }
                }
                if (until != null) {
                    stopped = steps.test(Values.map(templates.render(until, vars)), vars, sessions.cookies(key),
                            base);
                }
            }
        } finally {
            block.round = 0;
        }
        // 写了 until 却跑满 max 还没成立：数据可能不全。
        boolean truncated = until != null && !stopped;
        vars.putAll(gathered);
        LinkedHashMap<String, Object> loop = new LinkedHashMap<>();
        loop.put("index", rounds);
        loop.put("rounds", rounds);
        loop.put("truncated", truncated);
        vars.put(FlowSteps.LOOP, loop);
        if (draft != null) {
            LinkedHashMap<String, Object> piece = new LinkedHashMap<>();
            piece.put("type", "repeat");
            piece.put("rounds", rounds);
            piece.put("stopped", stopped ? "until" : "max");
            piece.put("incomplete", truncated);
            LinkedHashMap<String, Object> sizes = new LinkedHashMap<>();
            gathered.forEach((name, list) -> sizes.put(name, list.size()));
            piece.put("collected", sizes);
            draft.add(phase, at, elapsed(started), piece);
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
                request, credentials, scope.redactionValues(), start, scope.base);
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

    /** 一定会跑的步骤引用的登录名字必须有；if / repeat 里的不提前要求，也不为它重新登录，真用到时缺了再报。 */
    private void requireStepLoginRefs(LoginScope scope, List<Step> steps) {
        LinkedHashSet<String> must = new LinkedHashSet<>();
        for (Step step : steps) {
            if (!FlowSteps.isFlow(step)) {
                collectLoginNames(step, must);
            }
        }
        for (String name : must) {
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
        return internal(request, credentials, cookies, version, start, failure, null);
    }

    private ExecutionResult internal(
            ExecutionRequest request,
            Map<String, String> credentials,
            List<String> cookies,
            String version,
            long start,
            Throwable failure,
            URI base
    ) {
        Set<String> downloadTokens = currentDownloadTokens();
        Redactor logRedactor = downloadTokens.isEmpty()
                ? redactor(credentials, cookies)
                : redactor(credentials, cookies, false, downloadTokens);
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        LOG.log(Level.WARNING, "traceId={0} code=INTERNAL detail={1}",
                new Object[] {request.traceId(), scrubLogText(writer.toString(), logRedactor)});
        return finish(false, null, ErrorCode.INTERNAL, "内部错误", null, null, "内部错误", List.of(), version, request,
                credentials, cookies, start, base);
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
        return finish(success, data, code, message, index, type, summary, logs, version, request, credentials, cookies,
                start, null);
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
            long start,
            URI base
    ) {
        Redactor redactor = redactor(credentials, cookies);
        Set<String> downloadTokens = currentDownloadTokens();
        Redactor logRedactor = downloadTokens.isEmpty()
                ? redactor
                : redactor(credentials, cookies, false, downloadTokens);
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
                    scrubLogText(log.path(), logRedactor),
                    log.status(),
                    log.extractCount()));
        }
        ExecutionError error = null;
        if (!success) {
            error = new ExecutionError(
                    code,
                    HttpCalls.clipPublic(HttpCalls.presentUpstream(scrubLogText(message, logRedactor))),
                    index,
                    redactor.redact(type),
                    HttpCalls.clipPublic(HttpCalls.presentUpstream(scrubLogText(summary, logRedactor))));
        }
        Object masked = success ? OutputMask.apply(data, redactor) : null;
        // 没有追踪要落库时不必再把所有秘密展开一遍。
        if (DevTraceScope.current() != null && traceSink != null) {
            commitTrace(request, redactor(credentials, cookies, true, downloadTokens), base);
        }
        return new ExecutionResult(
                success,
                masked,
                error,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                scrubbedLogs,
                version,
                request.traceId());
    }

    private static Set<String> currentDownloadTokens() {
        FileScope scope = FileScope.current();
        return scope == null ? Set.of() : scope.generatedTokens();
    }

    private static String scrubLogText(String text, Redactor logRedactor) {
        if (text == null) {
            return null;
        }
        return maskDownloadUrl(logRedactor.redact(text));
    }

    private static String maskDownloadUrl(String text) {
        if (text == null || !containsFilesSegment(text)) {
            return text;
        }
        return DOWNLOAD_TOKEN.matcher(text).replaceAll(DOWNLOAD_MASK);
    }

    private static boolean containsFilesSegment(String text) {
        int limit = text.length() - 5;
        for (int i = 0; i <= limit; i++) {
            if (text.regionMatches(true, i, "files", 0, 5)) {
                return true;
            }
        }
        return false;
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
            doc = liveConch(request);
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
            String summary,
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
            case "set" -> DevTraceDraft.variablePiece(step.type(), namesOnly(step.def(), false));
            case "script" -> DevTraceDraft.scriptFailurePiece(
                    namesOnly(step.def(), true), problem == null ? null : problem.script);
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
            default -> {
                LinkedHashMap<String, Object> other = new LinkedHashMap<>();
                other.put("type", step.type());
                yield other;
            }
        };
        // 说明与这次调用错误是同一句。渲染时再打码并截断，这里不附加正文。
        stampFailure(piece, step.type(), summary);
        stampCredential(piece, credentialBearing);
        draft.add(phase, index, durationMs, piece);
    }

    /** 提取、set、if、repeat，以及没有抛出位置的脚本。已有 {@code scriptError} 的抛出保持原样。 */
    private static void stampFailure(Map<String, Object> piece, String type, String summary) {
        if (!"extract".equals(type)
                && !"set".equals(type)
                && !"script".equals(type)
                && !FlowSteps.IF.equals(type)
                && !FlowSteps.REPEAT.equals(type)) {
            return;
        }
        piece.put("passed", Boolean.FALSE);
        if (piece.containsKey("scriptError") || summary == null || summary.isBlank()) {
            return;
        }
        piece.put("error", summary);
    }

    private static Map<String, Object> namesOnly(Map<String, Object> def, boolean script) {
        LinkedHashMap<String, Object> names = new LinkedHashMap<>();
        if (def == null) {
            return names;
        }
        for (String key : def.keySet()) {
            if ("code".equals(key) || "timeout_ms".equals(key) || ConchRegistry.RESERVED.contains(key)) {
                continue;
            }
            // into 是脚本的目的地，不是这次写出来的变量。
            if (script && "into".equals(key)) {
                continue;
            }
            names.put(key, null);
        }
        return names;
    }

    private void commitTrace(ExecutionRequest request, Redactor redactor, URI base) {
        DevTraceDraft draft = DevTraceScope.current();
        if (draft == null || traceSink == null) {
            return;
        }
        SessionKey key = new SessionKey(request.origin(), request.conch(), request.account());
        List<String> names;
        boolean stored;
        try {
            names = base == null ? List.of() : sessions.cookieNames(key, base);
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
        return redactor(credentials, cookies, false, Set.of());
    }

    private Redactor redactor(
            Map<String, String> credentials,
            List<String> cookies,
            boolean includeTraceDerived,
            Collection<String> extraSecrets
    ) {
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
        if (extraSecrets != null) {
            for (String value : extraSecrets) {
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
        if (scope.bound != null) {
            vars.put(ConchRegistry.AUTH_VAR, scope.authView);
        }
        return vars;
    }

    private static URI tryBaseUri(ConchDoc conch, Map<String, String> config) {
        if (conch == null) {
            return null;
        }
        try {
            return baseUri(conch, config);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static URI baseUri(ConchDoc conch, Map<String, String> config) {
        if (AuthenticatorChecks.isAuthenticator(conch)) {
            // 授权器没有 base_url，请求都写完整地址。相对地址和按名字读 Cookie 以第一个生效的认证地址为准。
            for (String origin : AuthOrigins.effective(conch, config)) {
                try {
                    URI uri = HostNames.withoutTrailingDot(URI.create(origin));
                    if (uri.isAbsolute() && uri.getHost() != null) {
                        return uri;
                    }
                } catch (IllegalArgumentException ex) {
                    // 看下一个
                }
            }
            throw new RuleProblem(ErrorCode.INTERNAL, "授权器没有可用的认证地址", null, null, null);
        }
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
     * 能力请求在提取之前处理最终状态。401/403、登录重定向，以及 HttpCalls 已经拦住的 5xx，保持原映射。
     * 其它非 2xx（含 400）：后续有覆盖该状态码的断言/分支/until，或有 {@code on_fail: session_expired} 断言时放行；
     * 非 GET 的 3xx 且不是登录重定向时放行。其余 400 仍按 {@code INVALID_INPUT}，其它非 2xx 按 {@code UPSTREAM_ERROR}，
     * 避免直接进入 extract 抽成空列表。无法解析的 Location 停在 3xx 上时，GET 的调用错误说明未跟随，不单报状态码。
     */
    private static void rejectCapabilityStatus(
            boolean auth,
            Outcome outcome,
            int position,
            int index,
            String type,
            List<StepLog> logs,
            List<Step> definitions,
            Continuation continuation
    ) {
        if (auth) {
            return;
        }
        if (lostSession(outcome)) {
            throw new SessionLost();
        }
        if (outcome.status() == null) {
            return;
        }
        int status = outcome.status();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status >= 300 && status < 400 && !isGet(outcome.method())) {
            return;
        }
        if (statusExpectationCovers(definitions, position, status, continuation)) {
            return;
        }
        if (status == 400) {
            throw new StepFailure(ErrorCode.INVALID_INPUT, UPSTREAM_INPUT_REJECTED, index, type, logs);
        }
        if (unresolvedLocation(outcome)) {
            throw new StepFailure(ErrorCode.UPSTREAM_ERROR, HttpCalls.UNRESOLVED_LOCATION, index, type, logs);
        }
        throw new StepFailure(ErrorCode.UPSTREAM_ERROR, HttpCalls.upstreamStatus(status), index, type, logs);
    }

    /** 这次停住是因为 Location 无法解析，而不是普通的未跟随 3xx。 */
    private static boolean unresolvedLocation(Outcome outcome) {
        for (TraceWarning warning : outcome.warnings()) {
            if (TraceWarning.LOCATION_UNRESOLVED.equals(warning.code())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGet(String method) {
        return method == null || method.isBlank() || "GET".equalsIgnoreCase(method);
    }

    /**
     * 这次请求之后、下一次 request 之前，是否有 assert(on_fail: session_expired)、assert.status、
     * if.status 或外层 repeat.until.status 覆盖实际状态码。
     * 字面量数字或数字列表对不上则不算覆盖（带 on_fail: session_expired 的断言除外，一律放行交由后续判定）。
     * 含模板等非字面量时无法预判，交给后续步骤。
     */
    private static boolean statusExpectationCovers(
            List<Step> definitions,
            int requestIndex,
            int actual,
            Continuation continuation
    ) {
        return statusExpectationCoversFrom(definitions, requestIndex + 1, actual, continuation);
    }

    private static boolean statusExpectationCoversFrom(
            List<Step> definitions,
            int startIndex,
            int actual,
            Continuation continuation
    ) {
        for (int j = startIndex; j < definitions.size(); j++) {
            Step step = definitions.get(j);
            if ("request".equals(step.type())) {
                return false;
            }
            // if 按状态码分支：成立与否都由规则自己处理，不在这里按非 2xx 失败。
            if (FlowSteps.IF.equals(step.type())) {
                if (step.def().containsKey("status")) {
                    return true;
                }
                List<Step> thenBranch = FlowSteps.branch(step, "then");
                List<Step> elseBranch = FlowSteps.branch(step, "else");
                if (hasSessionExpiredAssertBeforeRequest(thenBranch)
                        || hasSessionExpiredAssertBeforeRequest(elseBranch)) {
                    return true;
                }
                if (containsRequest(thenBranch) || containsRequest(elseBranch)) {
                    return false;
                }
                continue;
            }
            if (FlowSteps.isFlow(step)) {
                return false;
            }
            if (!"assert".equals(step.type())) {
                continue;
            }
            if ("session_expired".equals(step.def().get("on_fail"))) {
                return true;
            }
            if (!step.def().containsKey("status")) {
                continue;
            }
            Boolean covered = literalStatusIncludes(step.def().get("status"), actual);
            if (covered == null || covered) {
                return true;
            }
        }
        if (continuation == null) {
            return false;
        }
        if (continuation.until() != null && continuation.until().containsKey("status")) {
            Boolean covered = literalStatusIncludes(continuation.until().get("status"), actual);
            if (covered == null || covered) {
                return true;
            }
        }
        if (continuation.hasNextRound()) {
            return false;
        }
        return statusExpectationCoversFrom(
                continuation.steps(), continuation.from(), actual, continuation.parent());
    }

    /** 在遇到下一次 request 或 repeat 之前，本分支（含嵌套 if）是否含 assert(on_fail: session_expired)。 */
    private static boolean hasSessionExpiredAssertBeforeRequest(List<Step> steps) {
        for (Step step : steps) {
            if ("request".equals(step.type())) {
                return false;
            }
            if (FlowSteps.IF.equals(step.type())) {
                List<Step> thenBranch = FlowSteps.branch(step, "then");
                List<Step> elseBranch = FlowSteps.branch(step, "else");
                if (hasSessionExpiredAssertBeforeRequest(thenBranch)
                        || hasSessionExpiredAssertBeforeRequest(elseBranch)) {
                    return true;
                }
                if (containsRequest(thenBranch) || containsRequest(elseBranch)) {
                    return false;
                }
                continue;
            }
            if (FlowSteps.isFlow(step)) {
                return false;
            }
            if ("assert".equals(step.type()) && "session_expired".equals(step.def().get("on_fail"))) {
                return true;
            }
        }
        return false;
    }

    /** 递归检查步骤列表（含嵌套 if / repeat）是否包含任何 request 步骤。 */
    private static boolean containsRequest(List<Step> steps) {
        for (Step step : steps) {
            if ("request".equals(step.type())) {
                return true;
            }
            if (FlowSteps.IF.equals(step.type())) {
                if (containsRequest(FlowSteps.branch(step, "then"))
                        || containsRequest(FlowSteps.branch(step, "else"))) {
                    return true;
                }
            } else if (FlowSteps.REPEAT.equals(step.type())) {
                if (containsRequest(FlowSteps.branch(step, "steps"))) {
                    return true;
                }
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

    private static List<Map<String, Object>> expiredWhenOf(ConchDoc conch) {
        if (conch == null || conch.auth() == null) {
            return List.of();
        }
        return conch.auth().expiredWhen();
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
        /** 这台 Crab 上生效的认证地址，不含 base_url。 */
        private Set<String> authOrigins;
        private final long deadline;
        private final LoginView view = new LoginView();
        /** 业务包：授权器账号的作用域。它的凭证只进它自己的登录步骤。 */
        private LoginScope bound;
        private final AuthView authView = new AuthView();
        private final boolean authenticator;
        /** 这次调用里探活通过过。 */
        private boolean probed;
        /** 这次调用读到的登录值，以及每次替换快照前后的值。清掉快照之后仍打码。 */
        private final LinkedHashSet<String> seenLogin = new LinkedHashSet<>();
        private boolean loggingIn;
        private boolean loggedInThisCall;
        /** 这次调用里带着身份已有的 Cookie 登录过。 */
        private boolean reusedCookies;
        /** 只持有共享锁：不能在这里登录。 */
        private boolean shared;

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
            this.authOrigins = AuthOrigins.effective(conch, config);
            this.deadline = deadline;
            this.authenticator = AuthenticatorChecks.isAuthenticator(conch);
        }

        /** 绑到同一个授权器账号的其它业务包的站点地址。 */
        private Set<String> siblingSites = Set.of();

        /** 认证地址的主机名。 */
        private Set<String> hosts() {
            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            for (String origin : authOrigins) {
                try {
                    String host = URI.create(origin).getHost();
                    if (host != null) {
                        hosts.add(host.toLowerCase(java.util.Locale.ROOT));
                    }
                } catch (IllegalArgumentException ex) {
                    // 不是地址，跳过
                }
            }
            return hosts;
        }

        /** 这次调用登录时会访问的地址：base_url 和生效的认证地址。互相顶掉登录按每个地址分别记。 */
        private List<String> addresses() {
            ArrayList<String> all = new ArrayList<>();
            String site = SiteAddress.of(base);
            if (site != null) {
                all.add(site);
            }
            for (String origin : authOrigins) {
                if (!origin.equals(site)) {
                    all.add(origin);
                }
            }
            return all;
        }

        private AuthReach reach(boolean login) {
            // 授权器的请求（含探活）都发往认证地址。
            return AuthReach.of(authOrigins, login || authenticator);
        }

        private String lookup(String name) {
            String stored = sessions.loginSnapshot(key).get(name);
            if (stored != null) {
                remember(stored);
                return stored;
            }
            String cookie = sessions.cookieValue(key, cookieName(name), base);
            if (cookie == null && authenticator) {
                for (String origin : authOrigins) {
                    try {
                        cookie = sessions.cookieValue(key, cookieName(name), URI.create(origin));
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (cookie != null) {
                        break;
                    }
                }
            }
            remember(cookie);
            return cookie;
        }

        /** 授权器导出的值。只能读授权器在 auth.exports 里声明的名字。 */
        private String exported(String name) {
            if (bound == null || !bound.conch.auth().exports().containsKey(name)) {
                return null;
            }
            return bound.lookup(name);
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
            if (bound != null) {
                // 授权器的凭证、登录变量和 Cookie 不在这个包的账号名下，一样要打码。
                values.addAll(bound.redactionValues());
                for (String value : bound.credentials.values()) {
                    if (value != null && !value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
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
            if (shared) {
                throw new NeedsExclusive();
            }
            login(this);
            if (lookup(name) == null) {
                throw missing(name);
            }
        }

        private StepFailure missing(String name) {
            return new StepFailure(ErrorCode.AUTH_FAILED, "登录没有建立变量 " + name, -1, "request", List.of());
        }

        private final class LoginView extends AbstractMap<String, Object>
                implements StepRunner.Peek, HttpCalls.SecretSource {
            @Override
            public Collection<String> knownValues() {
                LinkedHashSet<String> values = new LinkedHashSet<>();
                for (String value : sessions.loginSnapshot(key).values()) {
                    if (value != null && !value.isEmpty()) {
                        values.add(value);
                    }
                }
                for (String value : seenLogin) {
                    if (value != null && !value.isEmpty()) {
                        values.add(value);
                    }
                }
                return List.copyOf(values);
            }

            @Override
            public Object peek(String name) {
                return lookup(name);
            }

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

        /** {@code auth.名字}：授权器导出的值，只读，和 login.* 一样按秘密处理。 */
        private final class AuthView extends AbstractMap<String, Object>
                implements StepRunner.Peek, HttpCalls.SecretSource {
            @Override
            public Collection<String> knownValues() {
                if (bound == null) {
                    return List.of();
                }
                LinkedHashSet<String> values = new LinkedHashSet<>(bound.view.knownValues());
                for (String value : bound.credentials.values()) {
                    if (value != null && !value.isEmpty()) {
                        values.add(value);
                    }
                }
                return List.copyOf(values);
            }

            @Override
            public Object peek(String name) {
                return exported(name);
            }

            @Override
            public boolean containsKey(Object key) {
                return key instanceof String;
            }

            @Override
            public Object get(Object key) {
                if (!(key instanceof String name)) {
                    return null;
                }
                String value = exported(name);
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
                return "auth";
            }
        }
    }
}
