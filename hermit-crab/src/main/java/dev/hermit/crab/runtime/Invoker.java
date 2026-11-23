package dev.hermit.crab.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.HttpMethod;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.FileStageException;
import dev.hermit.crab.engine.ConchDigest;
import dev.hermit.crab.engine.ExecutionError;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.InputPreparer;
import dev.hermit.crab.engine.InvalidInput;
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
    public static final String INVALID_ACCOUNT_NAME = "账号名不合法。";
    /** 能力或规则包不存在。与控制台错误码「不存在」同一句，不再返回英文 not found。 */
    public static final String NOT_FOUND_MESSAGE = "不存在";
    public static final String CONFIRM_HEADER = "X-Hermit-Confirm";
    /** 当前规则包的快照令牌。不是秘密，只放在这次 409 的响应头和 message 里。 */
    public static final String CONFIRM_TOKEN_HEADER = "X-Hermit-Confirm-Token";
    /** 装在本机但当前校验不让载入的包。不是「不存在」，409 CONCH_DISABLED。 */
    public static final String RULE_DISABLED_CALL =
            "规则包已停用：这版规则没有通过当前校验，所有账号暂时都不能调用。到 Crab 控制台的规则包详情里更新、退回或改好后重新导入，再调用。";
    /** 整个规则包在本机被停用。409 CONCH_DISABLED，排在 Key 权限检查之前。 */
    public static final String CONCH_SWITCHED_OFF_CALL =
            "该规则包已在本机停用，调用前需要在 Crab 控制台的规则包详情里重新启用。";
    /** 等待确认期间规则包被更新、回滚或重新导入。409，不执行。 */
    public static final String CHANGED_AFTER_CONFIRM = "确认期间规则包已经变化，没有执行。请重新发起，并确认新版本。";
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
    private final InputPreparer inputs = new InputPreparer();
    /**
     * 同一幂等键正在执行时，后到的请求等它结束再查缓存。只有同一个键互相等待；
     * 执行结束就移除，不随历史键的数量增长（审计 A05）。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> idempotencyInFlight = new ConcurrentHashMap<>();
    private volatile ReportQueue reportQueue;
    private volatile KnownSecrets knownSecrets = KnownSecrets.empty();
    private volatile VersionSuccess versionSuccess;
    private volatile McpForwarder mcpForwarder;
    private volatile java.util.function.Predicate<String> ruleDisabled = name -> false;
    private volatile FileStage fileStage;
    private volatile String fileBase;
    private volatile java.util.function.Predicate<String> switchedOff = name -> false;
    private volatile java.util.function.Function<String, java.util.List<String>> rivals = name -> java.util.List.of();
    public static final String RIVAL_HINT = "本机另有规则包访问同一网站，可能互相顶掉登录，请到控制台查看。";

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

    /** 文件暂存区。{@code fallbackBase} 在拿不到请求地址时用来拼下载链接。 */
    public void bindFileStage(FileStage stage, String fallbackBase) {
        this.fileStage = stage;
        this.fileBase = fallbackBase;
    }

    /**
     * 文件入参统一换成 fileId：data URI 的小文件先存进暂存区，fileId 要求存在且属于这个 Key。
     * 之后的追踪、幂等和步骤模板看到的都只是 fileId。
     */
    public static Map<String, Object> stageFileInputs(Action capability, Map<String, Object> rawInput) {
        java.util.LinkedHashMap<String, Object> staged = null;
        for (Map.Entry<String, dev.hermit.common.model.ParamSpec> entry : capability.input().entrySet()) {
            if (!entry.getValue().file() || !(rawInput.get(entry.getKey()) instanceof String value)) {
                continue;
            }
            FileScope scope = FileScope.current();
            if (scope == null) {
                throw new FileStageException("这台 Crab 没有启用文件暂存区");
            }
            Optional<FileStage.Inline> inline = FileStage.inline(value);
            if (inline.isEmpty()) {
                scope.require(value);
                continue;
            }
            if (staged == null) {
                staged = new java.util.LinkedHashMap<>(rawInput);
            }
            staged.put(entry.getKey(), scope.store(
                    inline.get().bytes(), inline.get().name(), inline.get().mime()).fileId());
        }
        return staged == null ? rawInput : staged;
    }

    /**
     * 幂等重放前先核对 {@code fileId} 的 API Key 归属，防止另一把 Key 借幂等键读取别人的缓存响应。
     * 内联 {@code data:} URI 不在这里落盘，缓存未命中后再由 {@link #stageFileInputs} 写入暂存区。
     */
    private static void requireOwnedFileIds(Action capability, Map<String, Object> rawInput) {
        if (capability == null || rawInput == null) {
            return;
        }
        for (Map.Entry<String, dev.hermit.common.model.ParamSpec> entry : capability.input().entrySet()) {
            if (!entry.getValue().file() || !(rawInput.get(entry.getKey()) instanceof String value)) {
                continue;
            }
            FileScope scope = FileScope.current();
            if (scope == null) {
                throw new FileStageException("这台 Crab 没有启用文件暂存区");
            }
            if (value.regionMatches(true, 0, "data:", 0, 5)) {
                continue;
            }
            scope.require(value);
        }
    }

    /** 整个规则包的本机开关。停用的包不论 Key 范围一律拒绝。 */
    public void bindSwitchedOff(java.util.function.Predicate<String> lookup) {
        this.switchedOff = lookup == null ? name -> false : lookup;
    }

    /** 和某个包可能在互相顶掉登录的其它包名。会话失效的报错据此加一句提示。 */
    public void bindRivals(java.util.function.Function<String, java.util.List<String>> lookup) {
        this.rivals = lookup == null ? name -> java.util.List.of() : lookup;
    }

    /**
     * 会话失效、又有同网站的包可能在互相顶掉登录时，在报错后面加一句。
     * 只写出这把 Key 自己能调用的包名；一个都不能调用时不出现包名。
     */
    private String withRivalHint(ApiKeyPrincipal principal, String conch, String message) {
        java.util.List<String> names;
        try {
            names = rivals.apply(conch);
        } catch (RuntimeException ex) {
            return message;
        }
        if (names == null || names.isEmpty()) {
            return message;
        }
        java.util.ArrayList<String> visible = new java.util.ArrayList<>();
        for (String name : names) {
            if (callable(principal, name)) {
                visible.add(name);
            }
        }
        String hint = visible.isEmpty()
                ? RIVAL_HINT
                : "本机的规则包 " + String.join("、", visible) + " 访问同一网站，可能互相顶掉登录，请到控制台查看。";
        return (message == null || message.isBlank() ? "" : message + "。") + hint;
    }

    private boolean callable(ApiKeyPrincipal principal, String conch) {
        ConchRegistry.Active active = registry.active(conch).orElse(null);
        if (active == null) {
            return false;
        }
        if (active.origin() == Origin.LOCAL && !principal.scope().allowsLocal(conch)) {
            return false;
        }
        for (Map.Entry<String, Action> entry : active.doc().actions().entrySet()) {
            Action capability = entry.getValue();
            if (principal.permits(conch, entry.getKey(), capability.group(), CapabilityHttp.readOnly(capability))) {
                return true;
            }
        }
        return false;
    }

    /** 包不在注册表里时，用它分清「已停用」和「不存在」。 */
    public void bindRuleDisabled(java.util.function.Predicate<String> lookup) {
        this.ruleDisabled = lookup == null ? name -> false : lookup;
    }

    /** {@code driver: mcp} 走转发器。没绑定时这类包失败关闭，不会落到 HTTP 步骤引擎。 */
    public void bindMcpForwarder(McpForwarder forwarder) {
        this.mcpForwarder = forwarder;
    }

    /** Successful official calls record the executed version so retention can keep it. */
    public void bindVersionSuccess(DataSource dataSource, Clock clock) {
        this.versionSuccess = dataSource == null ? null : new VersionSuccess(dataSource, clock);
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
        return invoke(InvokeRequest.of(principal, conch, action, input, traceId)
                .account(account)
                .idempotency(idempotencyKey, null));
    }

    public InvokeOutcome invoke(InvokeRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        // 这次调用能用的暂存区文件按 API Key 划分，引擎发请求时从线程上取。
        ApiKeyPrincipal principal = request.principal();
        FileScope previous = FileScope.begin(fileStage, principal == null ? null : principal.id(), fileBase);
        try {
            return invokeScoped(request);
        } finally {
            FileScope.restore(previous);
        }
    }

    private InvokeOutcome invokeScoped(InvokeRequest request) {
        ApiKeyPrincipal principal = request.principal();
        String conch = request.conch();
        String action = request.action();
        Map<String, Object> input = request.input();
        Optional<String> account = request.account();
        Optional<String> idempotencyKey = request.idempotencyKey();
        String bodySha256 = request.bodySha256();
        CallChannel channel = request.channel();
        String traceId = request.traceId();
        boolean httpConfirmed = request.httpConfirmed();
        ConchDoc confirmedAgainst = request.confirmedAgainst();
        Origin confirmedOrigin = request.confirmedOrigin();
        String confirmToken = request.confirmToken();
        String httpMethod = request.httpMethod();
        if (principal == null || conch == null || action == null || conch.length() > NAME_LIMIT || action.length() > NAME_LIMIT) {
            return requestOnly(traceId, ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        CallChannel recorded = channel == null ? CallChannel.HTTP : channel;
        // 墓碑只表示 Reef 不再分发。包仍在注册表里时，调用继续走后面的权限和能力检查。
        // 注册表里已经没有这份包（旧数据清掉了 current）才返回 CONCH_DISABLED。
        // 已经带着授权快照进入引擎的请求仍执行那份快照（审计 A01）。
        // 文档和来源取自同一个快照：同名的本地包和正式包切换时，不会拿一边的规则配另一边的账号。
        ConchRegistry.Active live = registry.active(conch).orElse(null);
        ConchDoc doc = live == null ? null : live.doc();
        Origin origin = live == null ? null : live.origin();
        if (doc == null && disabledConches.contains(conch)) {
            return requestOnly(traceId, ErrorCode.CONCH_DISABLED, DisabledConches.CALL_MESSAGE);
        }
        if (doc == null && ruleDisabled.test(conch)) {
            return requestOnly(traceId, ErrorCode.CONCH_DISABLED, RULE_DISABLED_CALL);
        }
        if (confirmedAgainst != null && doc != null
                && (!sameSnapshot(doc, confirmedAgainst)
                        || (confirmedOrigin != null && origin != confirmedOrigin))) {
            return requestOnly(traceId, ErrorCode.CONFLICT, CHANGED_AFTER_CONFIRM);
        }
        Action capability = doc == null ? null : doc.actions().get(action);
        if (doc == null || capability == null) {
            return requestOnly(traceId, ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (recorded == CallChannel.MCP && !capability.mcp().expose()) {
            return requestOnly(traceId, ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (switchedOff.test(conch)) {
            return requestOnly(traceId, ErrorCode.CONCH_DISABLED, CONCH_SWITCHED_OFF_CALL);
        }
        if (disabled.disabled(conch, action)) {
            return requestOnly(traceId, ErrorCode.CAPABILITY_DISABLED, CAPABILITY_DISABLED_MESSAGE);
        }
        if (recorded == CallChannel.HTTP && httpMethod != null) {
            String expected = CapabilityHttp.method(capability).name();
            if (!expected.equals(httpMethod)) {
                return methodNotAllowed(traceId, expected);
            }
        }
        // grantsAllOfficial（all: true，或旧的空 conches 且空 actions）包含本地包。
        // 写了 conches 或 actions 的 Key 仍要求本地包出现在 conches 里。只读由后面的 permits 限制。
        if (origin == Origin.LOCAL && !principal.scope().allowsLocal(conch)) {
            return requestOnly(traceId, ErrorCode.FORBIDDEN, "forbidden");
        }
        if (!principal.permits(conch, action, capability.group(), CapabilityHttp.readOnly(capability))) {
            return requestOnly(traceId, ErrorCode.FORBIDDEN, "forbidden");
        }
        if (recorded == CallChannel.MCP && capability.effectiveConfirm() && confirmedAgainst == null) {
            return requestOnly(traceId, ErrorCode.CONFLICT, CHANGED_AFTER_CONFIRM);
        }
        Account resolved;
        try {
            resolved = accounts.resolve(origin, conch, account == null ? Optional.empty() : account);
        } catch (AccountNotFoundException ex) {
            if (ex.account() == null && accounts.list(origin, conch).isEmpty()) {
                return requestOnly(traceId, ErrorCode.NOT_CONFIGURED, CredentialMessages.NO_ACCOUNT);
            }
            return requestOnly(traceId, ErrorCode.ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND_MESSAGE);
        } catch (IllegalArgumentException ex) {
            return requestOnly(traceId, ErrorCode.INVALID_INPUT, INVALID_ACCOUNT_NAME);
        }
        Map<String, String> stored = config.all(origin, conch);
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            return requestOnly(traceId, ErrorCode.NOT_CONFIGURED, missingConfig(missingConfig));
        }
        List<String> missingCredentials = DeclaredSettings.missingCredentials(
                doc, vault.fieldNames(origin, conch, resolved.account()));
        if (!missingCredentials.isEmpty()) {
            return requestOnly(traceId, ErrorCode.NOT_CONFIGURED,
                    CredentialMessages.missing(resolved.account(), missingCredentials, doc.credentials()));
        }
        Map<String, String> merged = DeclaredSettings.mergedConfig(doc, stored);
        Map<String, String> secrets;
        try {
            secrets = vault.decryptForExecution(origin, conch, resolved.account());
        } catch (CredentialDecryptException ex) {
            return requestOnly(traceId, ErrorCode.NOT_CONFIGURED, CredentialDecryptException.MESSAGE);
        }
        Map<String, Object> rawInput = input == null ? Map.of() : input;
        // 入参不合法（含未声明的字段）在执行前就拒：不开追踪、不写调用记录，只给请求编号。
        try {
            inputs.prepare(capability, rawInput);
        } catch (InvalidInput ex) {
            return requestOnly(traceId, ErrorCode.INVALID_INPUT, ex.getMessage());
        }
        if (recorded == CallChannel.HTTP) {
            InvokeOutcome confirm = httpConfirm(doc, capability, httpConfirmed, confirmToken, traceId);
            if (confirm != null) {
                return confirm;
            }
        }
        if (CapabilityHttp.method(capability) == HttpMethod.POST && idempotencyKey != null && idempotencyKey.isPresent()) {
            return replayOrExecute(
                    principal, doc, origin, action, rawInput, resolved, secrets, merged,
                    idempotencyKey.get(), bodySha256, recorded, traceId);
        }
        // 文件入参放在确认之后处理：要确认的调用不提前占暂存区；
        // 幂等路径会先核对 fileId 归属再查缓存，data URI 在缓存未命中后才落盘。
        try {
            rawInput = stageFileInputs(capability, rawInput);
        } catch (FileStageException ex) {
            return requestOnly(traceId, ErrorCode.INVALID_INPUT, ex.getMessage());
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
        Action capability = doc.actions().get(action);
        try {
            requireOwnedFileIds(capability, input);
        } catch (FileStageException ex) {
            return requestOnly(traceId, ErrorCode.INVALID_INPUT, ex.getMessage());
        }
        String lockId = origin.name() + "\0" + doc.conch() + "\0" + account.account() + "\0" + action + "\0" + key;
        CompletableFuture<Void> mine = new CompletableFuture<>();
        while (true) {
            CompletableFuture<Void> running = idempotencyInFlight.putIfAbsent(lockId, mine);
            if (running == null) {
                break;
            }
            try {
                running.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return requestOnly(traceId, ErrorCode.INTERNAL, "内部错误");
            } catch (ExecutionException ignored) {
                // 前一个请求失败也只是让出位置，这里重新查缓存。
            }
        }
        try {
            Optional<IdempotencyStore.Cached> stored = idempotency.findFresh(
                    origin, doc.conch(), account.account(), action, key);
            if (stored.isPresent()) {
                if (!sameBody(stored.get().bodySha256(), bodySha256)) {
                    return requestOnly(traceId, ErrorCode.IDEMPOTENCY_CONFLICT, IDEMPOTENCY_CONFLICT);
                }
                return replay(stored.get().envelope());
            }
            Map<String, Object> staged;
            try {
                staged = stageFileInputs(capability, input);
            } catch (FileStageException ex) {
                return requestOnly(traceId, ErrorCode.INVALID_INPUT, ex.getMessage());
            }
            InvokeOutcome outcome = execute(
                    principal, doc, origin, action, staged, account, secrets, merged, Optional.of(key), channel, traceId);
            if (!loginInputRequired(outcome)) {
                idempotency.save(
                        origin,
                        doc.conch(),
                        account.account(),
                        action,
                        key,
                        new String(outcome.body(), java.nio.charset.StandardCharsets.UTF_8),
                        bodySha256);
            }
            return outcome;
        } finally {
            idempotencyInFlight.remove(lockId, mine);
            mine.complete(null);
        }
    }

    /** Same instance, or the same content reloaded (records compare by value). */
    public static boolean sameSnapshot(ConchDoc current, ConchDoc confirmed) {
        return current == confirmed || current.equals(confirmed);
    }

    /**
     * 当前文档的快照令牌：规范 JSON 的 SHA-256，小写十六进制。
     * 同一对象或同一份内容两次计算相同。本地覆盖可以不改版本号，所以不能只比版本。
     */
    public static String snapshotToken(ConchDoc doc) {
        return ConchDigest.sha256(doc);
    }

    /** 待输入登录不能缓存 24 小时，否则同一幂等键在用户提交之后仍回放 409。 */
    private static boolean loginInputRequired(InvokeOutcome outcome) {
        if (outcome == null || outcome.body() == null) {
            return false;
        }
        JsonNode code = ApiJson.tree(outcome.body()).path("error").path("code");
        return code.isTextual() && ErrorCode.LOGIN_INPUT_REQUIRED.name().equals(code.asText());
    }

    /** 需要确认、但还没带上匹配令牌时的 message。令牌写在句子里，响应头用同一值。 */
    public static String confirmRequired(String token) {
        return "该操作需要确认。请带上请求头 " + CONFIRM_HEADER + ": true 和 "
                + CONFIRM_TOKEN_HEADER + ": " + token + " 后重试。";
    }

    /** 上面这句里的令牌。其它 message 返回 null。 */
    public static String issuedConfirmToken(String message) {
        if (message == null || !message.startsWith("该操作需要确认。")) {
            return null;
        }
        String marker = CONFIRM_TOKEN_HEADER + ": ";
        int at = message.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int start = at + marker.length();
        int end = message.indexOf(' ', start);
        if (end < 0) {
            end = message.length();
        }
        String token = message.substring(start, end);
        if (token.length() == 64 && token.chars().allMatch(Invoker::lowerHex)) {
            return token;
        }
        return null;
    }

    /**
     * 需要确认却还没确认，或带了和当前文档不一致的令牌。null 表示可以执行这份 {@code doc}。
     * 不查、不写幂等缓存。
     */
    private static InvokeOutcome httpConfirm(
            ConchDoc doc,
            Action capability,
            boolean httpConfirmed,
            String confirmToken,
            String traceId
    ) {
        String presented = presentedConfirmToken(confirmToken);
        boolean needsConfirm = capability.effectiveConfirm();
        if (!needsConfirm && presented == null) {
            return null;
        }
        String snapshot = snapshotToken(doc);
        if (presented != null && !presented.equals(snapshot)) {
            return requestOnly(traceId, ErrorCode.CONFLICT, CHANGED_AFTER_CONFIRM);
        }
        if (needsConfirm && (!httpConfirmed || presented == null)) {
            return requestOnly(traceId, ErrorCode.CONFLICT, confirmRequired(snapshot));
        }
        return null;
    }

    private static String presentedConfirmToken(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean lowerHex(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
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
        String message = result.error().message();
        if (result.error().code() == ErrorCode.SESSION_EXPIRED) {
            message = withRivalHint(principal, doc.conch(), message);
        }
        return done(traceId, result.error().code(), message, errorData, payload);
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
            VersionSuccess recorder = versionSuccess;
            if (recorder != null) {
                try {
                    recorder.record(invocation.conch(), version);
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

    /** 与详情页「缺少必填配置：」同一句，字段名用声明顺序，逗号后有空格。 */
    public static String missingConfig(java.util.List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return "缺少必填配置";
        }
        return "缺少必填配置：" + String.join(", ", fields);
    }

    /** Rejected before execution. The id is a request number, not a stored trace, and the header is omitted. */
    private static InvokeOutcome requestOnly(String requestId, ErrorCode code, String message) {
        int status = StatusCodes.of(code);
        String json = ApiEnvelope.requestJson(null, code, message, requestId == null ? "" : requestId);
        return new InvokeOutcome(status, ApiJson.bytes(json), null, false, null, true);
    }

    private static InvokeOutcome methodNotAllowed(String requestId, String expected) {
        String message = "这个能力要用 " + expected + " 调用。";
        String json = ApiEnvelope.requestJson(null, ErrorCode.INVALID_INPUT, message, requestId == null ? "" : requestId);
        return new InvokeOutcome(405, ApiJson.bytes(json), null, false, null, true, expected);
    }

    private static InvokeOutcome done(String traceId, ErrorCode code, String message, Object data) {
        return done(traceId, code, message, data, null);
    }

    private static InvokeOutcome done(String traceId, ErrorCode code, String message, Object data, McpCallPayload payload) {
        int status = code == null ? 200 : StatusCodes.of(code);
        String json = ApiEnvelope.json(data, code, message, traceId);
        return new InvokeOutcome(status, ApiJson.bytes(json), traceId, false, payload);
    }

    /** Keys currently executing. Empty once every call has returned. */
    int idempotencyInFlight() {
        return idempotencyInFlight.size();
    }

    /** Capability disable flags, config file union in-memory switches. */
    public interface CapabilityGate {
        boolean disabled(String conch, String action);
    }
}
