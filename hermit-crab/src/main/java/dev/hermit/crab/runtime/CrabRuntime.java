package dev.hermit.crab.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.EngineSettings;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.InvalidInput;
import dev.hermit.crab.engine.InputPreparer;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.upstream.McpCallPayload;
import dev.hermit.crab.upstream.McpForwarder;
import dev.hermit.crab.upstream.McpOAuth;
import dev.hermit.crab.upstream.McpRefresh;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.report.LiveReportSettings;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.report.ReportSender;
import dev.hermit.crab.report.ReportSettings;
import dev.hermit.crab.report.ReportSettingsStore;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.secure.MasterKeyException;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.Account;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyService;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CapabilitySwitchStore;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.tls.TlsHostStore;
import dev.hermit.crab.tls.TlsTrust;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.DebugStateStore;
import dev.hermit.crab.store.DevTraceStore;
import dev.hermit.crab.store.EncryptedLoginSnapshotStore;
import dev.hermit.crab.store.EncryptedSessionPersistence;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.store.ConsolePassword;
import dev.hermit.crab.sync.ConchCatalog;
import dev.hermit.crab.sync.ConchOutcome;
import dev.hermit.crab.sync.CredentialDeclarationChange;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.sync.InstalledConches;
import dev.hermit.crab.sync.LocalConches;
import dev.hermit.crab.sync.LoadReport;
import dev.hermit.crab.sync.UpdateResult;
import dev.hermit.crab.sync.ConchSync;
import dev.hermit.crab.sync.SubscriptionCommands;
import dev.hermit.crab.sync.SubscriptionException;
import dev.hermit.crab.sync.Subscriptions;
import dev.hermit.crab.sync.SyncReport;
import dev.hermit.crab.sync.SyncSettings;

/**
 * Composition root for one Crab process. {@link #loadInstalledConches()} verifies packages when a
 * Reef public key is configured and binds sync and report hooks when {@code reef.url}
 * is set. Listeners run after every engine execution.
 */
public final class CrabRuntime implements AutoCloseable, Invoker.CapabilityGate {
    private static final Logger LOG = Logger.getLogger(CrabRuntime.class.getName());
    private static final Duration CALL_LOG_RETENTION = Duration.ofDays(30);

    private final CrabPaths paths;
    private final CrabConfig config;
    private final Clock clock;
    private final CrabDatabase database;
    private final CredentialCipher cipher;
    private final AccountStore accounts;
    private final CredentialVault vault;
    private final ConchConfigStore conchConfig;
    private final ApiKeyService apiKeys;
    private final ConchRegistry registry = new ConchRegistry();
    private final EngineSettings settings;
    private final SessionManager sessions;
    private final RuleEngine engine;
    private final McpForwarder mcpForwarder;
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilitySwitchStore switches;
    private final DebugStateStore debug;
    private final DevTraceStore devTraces;
    private final ConsolePassword consolePassword;
    private final ConsoleSessions consoleSessions;
    private final Set<String> configDisabled;
    private final Set<String> switchDisabled;
    private final List<Runnable> catalogListeners = new CopyOnWriteArrayList<>();
    private final TraceMemory traceMemory = new TraceMemory();
    private final DisabledConches disabledConches;
    private final Subscriptions subscriptions;
    private final Invoker invoker;
    private final ConchAdmin admin;
    private final LocalConches localConches;
    private final String instanceId;
    private final OutboundTls outboundTls;
    private final OutboundTls previousTls;
    private final TlsTrust tlsTrust;
    private final KnownSecrets knownSecrets = KnownSecrets.empty();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean prepared = new AtomicBoolean();
    private final AtomicBoolean serving = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private PublicKey reefKey;
    private ConchSync conchSync;
    private LiveReportSettings liveReport;
    private ReportQueue reportQueue;
    private ReportSender reportSender;
    private volatile boolean reportEnabled;
    private volatile int reportIntervalSeconds;
    /** Last completed sync, from startup, the timer, or a manual request. Sync threads write; HTTP threads read. */
    private volatile SyncReport lastSync;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService syncScheduler;
    private ScheduledExecutorService reportScheduler;
    private ScheduledExecutorService memoryScheduler;
    private ScheduledFuture<?> reportSendTask;

    public interface ExecutionListener {
        void onResult(Invocation invocation, ExecutionResult result);
    }

    private CrabRuntime(
            CrabPaths paths,
            CrabConfig config,
            Function<String, String> environment,
            Clock clock,
            OutboundTls outboundTls,
            OutboundTls previousTls
    ) {
        this.outboundTls = outboundTls;
        this.previousTls = previousTls;
        config = ReefBootstrap.overlay(paths, config);
        this.paths = paths;
        this.config = config;
        this.clock = clock;
        this.reportEnabled = config.reportEnabled();
        this.reportIntervalSeconds = config.reportIntervalSeconds();
        this.database = new CrabDatabase(paths);
        byte[] masterKey;
        try {
            masterKey = loadMasterKey(paths, environment, database);
        } catch (RuntimeException ex) {
            this.database.close();
            throw ex;
        }
        try {
            this.cipher = new CredentialCipher(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
        this.accounts = new AccountStore(database.dataSource());
        this.vault = new CredentialVault(database.dataSource(), cipher);
        if (this.vault.anyUnreadable()) {
            LOG.warning(CredentialDecryptException.MESSAGE + "。主密钥与已保存的凭证不匹配，请重新配置账号凭证。");
        }
        this.conchConfig = new ConchConfigStore(database.dataSource());
        this.apiKeys = new ApiKeyService(database.dataSource());
        this.settings = config.engineSettings();
        this.sessions = new SessionManager(
                new EncryptedSessionPersistence(database.dataSource(), cipher),
                new EncryptedLoginSnapshotStore(database.dataSource(), cipher),
                settings,
                clock);
        this.engine = new RuleEngine(registry, sessions, settings, knownSecrets);
        this.mcpForwarder = new McpForwarder(
                sessions,
                knownSecrets,
                settings.capabilityTimeout(),
                config.mcpMaxConcurrencyPerAccount(),
                vault,
                clock,
                settings.headers());
        this.callLogs = new CallLogStore(database.dataSource(), clock);
        this.idempotency = new IdempotencyStore(database.dataSource(), cipher, clock);
        this.switches = new CapabilitySwitchStore(database.dataSource());
        this.debug = new DebugStateStore(database.dataSource(), clock, this::onDebugChanged);
        this.devTraces = new DevTraceStore(database.dataSource(), clock, debug);
        this.mcpForwarder.bindDevTraces(devTraces);
        this.engine.bindDevTraceSink(new DevTraceBinding(debug, devTraces));
        this.consolePassword = new ConsolePassword(database.dataSource(), clock);
        this.consoleSessions = new ConsoleSessions(clock);
        this.configDisabled = Set.copyOf(config.disabledCapabilities());
        this.switchDisabled = ConcurrentHashMap.newKeySet();
        this.switchDisabled.addAll(switches.disabledKeys(Origin.OFFICIAL));
        this.switchDisabled.addAll(switches.disabledKeys(Origin.LOCAL));
        this.disabledConches = new DisabledConches(database.dataSource());
        this.disabledConches.load();
        this.registry.onRemove(name -> sessions.pending().discardConch(name));
        this.disabledConches.onDisabled(name -> sessions.pending().discardConch(name));
        this.subscriptions = new Subscriptions(database.dataSource());
        this.invoker = new Invoker(
                registry, accounts, vault, conchConfig, engine, callLogs, idempotency, this, disabledConches);
        this.invoker.bindKnownSecrets(knownSecrets);
        this.invoker.bindMcpForwarder(mcpForwarder);
        this.invoker.bindRuleDisabled(this::ruleDisabled);
        this.admin = new ConchAdmin(
                database.dataSource(), accounts, vault, conchConfig, apiKeys, sessions, engine, registry, disabledConches);
        this.localConches = new LocalConches(
                paths, registry, database.dataSource(), () -> debug.get().enabled(), clock);
        this.localConches.onWidened(this::clearWidenedSessions);
        this.admin.bindOriginFallback(name -> localConches.occupies(name) ? Origin.LOCAL : null);
        this.admin.bindAccountRemoved(mcpForwarder::forgetAccount);
        this.instanceId = InstanceIds.loadOrCreate(paths);
        Path caDir = this.config.tlsCaDir() == null
                ? paths.trustCa()
                : this.config.tlsCaDirectory(paths.root());
        TlsHostStore tlsHosts = new TlsHostStore(database.dataSource());
        this.tlsTrust = new TlsTrust(
                outboundTls,
                tlsHosts,
                database.dataSource(),
                clock,
                caDir,
                this.config.tlsSystemTrust(),
                this::probeAllowed);
        try {
            String initialPassword = environment == null ? null : environment.apply(ConsolePassword.ENV);
            this.consolePassword.applyInitial(initialPassword);
            this.outboundTls.setPins(tlsHosts);
        } catch (RuntimeException ex) {
            tlsTrust.close();
            this.mcpForwarder.close();
            this.database.close();
            throw ex;
        }
    }

    public TlsTrust tlsTrust() {
        return tlsTrust;
    }

    /** 检测只允许已加载规则的 base_url、MCP 上游和 OAuth 端点，以及 Reef 地址。 */
    private boolean probeAllowed(String host, int port) {
        return probeTargets().contains(host + ":" + port);
    }

    private Set<String> probeTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (ConchDoc doc : registry.snapshot().values()) {
            addProbeTarget(targets, doc.baseUrl());
            McpUpstream upstream = doc.upstream();
            if (upstream == null) {
                continue;
            }
            addProbeTarget(targets, upstream.url());
            int upstreamPort = portOf(upstream.url());
            for (String allowed : upstream.allowedHosts()) {
                addAllowedHost(targets, allowed, upstreamPort);
            }
            McpAuth auth = upstream.auth();
            if (auth != null) {
                addProbeTarget(targets, auth.authorizationEndpoint());
                addProbeTarget(targets, auth.tokenEndpoint());
            }
        }
        addProbeTarget(targets, config.reefUrl());
        ConchSync sync = conchSync;
        if (sync != null && sync.currentSettings() != null && sync.currentSettings().reefUrl() != null) {
            addProbeTarget(targets, sync.currentSettings().reefUrl().toString());
        }
        return targets;
    }

    private static void addProbeTarget(Set<String> targets, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (uri.getHost() == null) {
            return;
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
        }
        if (port < 1) {
            return;
        }
        targets.add(uri.getHost().toLowerCase(Locale.ROOT) + ":" + port);
    }

    private static int portOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            URI uri = URI.create(raw.trim());
            if (uri.getPort() > 0) {
                return uri.getPort();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return 443;
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return 80;
            }
        } catch (IllegalArgumentException ex) {
            return -1;
        }
        return -1;
    }

    private static void addAllowedHost(Set<String> targets, String raw, int upstreamPort) {
        if (raw == null || raw.isBlank() || upstreamPort < 1) {
            return;
        }
        String value = raw.trim();
        int split = value.lastIndexOf(':');
        if (split > 0 && value.indexOf(':') == split && value.substring(split + 1).chars().allMatch(Character::isDigit)) {
            try {
                int port = Integer.parseInt(value.substring(split + 1));
                if (port >= 1 && port <= 65535) {
                    targets.add(value.substring(0, split).toLowerCase(Locale.ROOT) + ":" + port);
                }
            } catch (NumberFormatException ex) {
                targets.add(value.toLowerCase(Locale.ROOT) + ":" + upstreamPort);
            }
            return;
        }
        targets.add(value.toLowerCase(Locale.ROOT) + ":" + upstreamPort);
    }

    /** 地址范围扩大的本地覆盖：先丢掉待输入登录，再清每个账号的会话和登录快照。 */
    private void clearWidenedSessions(String conch) {
        sessions.pending().discardConch(conch);
        for (Account account : accounts.list(Origin.LOCAL, conch)) {
            sessions.invalidate(new SessionKey(Origin.LOCAL, conch, account.account()));
        }
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config) {
        return open(paths, config, System::getenv, Clock.systemUTC());
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config, Function<String, String> environment) {
        return open(paths, config, environment, Clock.systemUTC());
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config, Function<String, String> environment, Clock clock) {
        Path caDir = config.tlsCaDir() == null ? paths.trustCa() : config.tlsCaDirectory(paths.root());
        OutboundTls tls = OutboundTls.open(caDir, config.tlsSystemTrust());
        OutboundTls previous = OutboundTls.install(tls);
        try {
            return new CrabRuntime(paths, config, environment, clock, tls, previous);
        } catch (RuntimeException ex) {
            OutboundTls.restore(previous);
            throw ex;
        }
    }

    /**
     * {@code HERMIT_MASTER_KEY} wins. Otherwise an existing {@code data/master.key} is used.
     * A missing file is generated only when no credential ciphertext is stored. Generating a new
     * key over existing rows would make those credentials permanently undecryptable.
     */
    private static byte[] loadMasterKey(CrabPaths paths, Function<String, String> environment, CrabDatabase database) {
        if (environment.apply(MasterKeyProvider.ENV) == null
                && !Files.isRegularFile(paths.masterKeyFile())
                && CredentialVault.hasCiphertext(database.dataSource())) {
            throw new MasterKeyException(MasterKeyException.MISSING_WITH_CREDENTIALS);
        }
        return MasterKeyProvider.load(paths, Optional.empty(), environment);
    }

    public CrabPaths paths() {
        return paths;
    }

    public CrabConfig config() {
        return config;
    }

    public String instanceId() {
        return instanceId;
    }

    public ConchRegistry registry() {
        return registry;
    }

    public DisabledConches disabledConches() {
        return disabledConches;
    }

    public Invoker invoker() {
        return invoker;
    }

    public ConchAdmin admin() {
        return admin;
    }

    public McpOAuth oauth() {
        return mcpForwarder.oauth();
    }

    public ApiKeyService apiKeys() {
        return apiKeys;
    }

    public CallLogStore callLogs() {
        return callLogs;
    }

    /** Newest-first config audit. The result has no secret values and no stored detail text. */
    public AuditLog.Page configAudit(String conch, String account, int page, int limit) {
        return AuditLog.page(database.dataSource(), conch, account, page, limit);
    }

    public DebugStateStore debug() {
        return debug;
    }

    public DevTraceStore devTraces() {
        return devTraces;
    }

    /** Shown when a trial account name is not a single token. The value is not repeated. */
    public static final String TRIAL_ACCOUNT_INVALID = "账号名不合法。";
    /** 试跑在执行前找不到本地包或能力。控制台和 MCP 用同一句。 */
    public static final String TRIAL_NOT_FOUND = "没有这个本地规则包或能力。";

    /** Records a trial that was refused before execution, so a later debug_trace can explain it. */
    public void noteRejectedTrial(String traceId) {
        traceMemory.reject(traceId);
    }

    public boolean rejectedTrial(String traceId) {
        return traceMemory.rejected(traceId);
    }

    public boolean deletedTrace(String traceId) {
        return traceMemory.deleted(traceId);
    }

    /**
     * Runs one local HTTP or MCP action without reporting to Tide.
     * Official packages, developer mode that is off, and a disabled capability are rejected
     * before execution. MCP does not go through {@code Invoker}, so there is no confirm header.
     * {@code apiKeyId} is recorded only for {@code conch_trial_run}; console and CLI trials pass null.
     */
    public ExecutionResult trialLocal(
            String conch,
            String action,
            Optional<String> account,
            Map<String, Object> input,
            String traceId
    ) {
        return trialLocal(conch, action, account, input, traceId, null);
    }

    public ExecutionResult trialLocal(
            String conch,
            String action,
            Optional<String> account,
            Map<String, Object> input,
            String traceId,
            String apiKeyId
    ) {
        return trialLocal(conch, action, account, input, traceId, apiKeyId, null);
    }

    /**
     * {@code confirmedAgainst} 是 MCP 已经据此让用户确认过的那份本地包。确认期间被重新导入时拒绝，
     * 不执行没确认过的内容。控制台试跑和不需要确认的试跑传 null。
     */
    public ExecutionResult trialLocal(
            String conch,
            String action,
            Optional<String> account,
            Map<String, Object> input,
            String traceId,
            String apiKeyId,
            ConchDoc confirmedAgainst
    ) {
        String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        ConchDoc doc = requireLocalDebug(conch);
        if (confirmedAgainst != null && !Invoker.sameSnapshot(doc, confirmedAgainst)) {
            throw new TrialRejected(409, ErrorCode.CONFLICT, Invoker.CHANGED_AFTER_CONFIRM, true);
        }
        if (!"http".equals(doc.driver()) && !"mcp".equals(doc.driver())) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, "只支持 HTTP 或 MCP 规则包的试跑。", true);
        }
        Action capability = doc.actions().get(action);
        if (capability == null) {
            throw new TrialRejected(404, ErrorCode.NOT_FOUND, TRIAL_NOT_FOUND, true);
        }
        if (disabled(conch, action)) {
            throw new TrialRejected(404, ErrorCode.CAPABILITY_DISABLED, Invoker.CAPABILITY_DISABLED_MESSAGE, true);
        }
        try {
            new InputPreparer().prepare(capability, input == null ? Map.of() : input);
        } catch (InvalidInput ex) {
            throw new TrialRejected(400, ErrorCode.INVALID_INPUT, ex.getMessage(), true);
        }
        ReadyAccount ready = openAccount(doc, account);
        ExecutionRequest request = new ExecutionRequest(
                id,
                Origin.LOCAL,
                doc.conch(),
                action,
                input == null ? Map.of() : input,
                ready.account(),
                new MappedAccount(ready.secrets(), ready.config()));
        ExecutionResult result = "mcp".equals(doc.driver())
                ? mcpForwarder.execute(request, doc)
                : engine.execute(request, doc);
        logTrial(result, ready.secrets(), doc.conch(), action, ready.account(), apiKeyId);
        return httpView(result);
    }

    /**
     * Rewrites a local MCP package from upstream {@code tools/list}.
     * Official packages are rejected before the debug switch. This does not write a call log.
     */
    public McpRefresh.Outcome refreshLocalMcp(
            String conch,
            Optional<String> account,
            String actor,
            String traceId
    ) {
        String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        ConchDoc doc = requireLocalDebug(conch);
        if (!"mcp".equals(doc.driver())) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, "只支持刷新 MCP 规则包。", true);
        }
        ReadyAccount ready = openAccount(doc, account);
        ExecutionRequest request = new ExecutionRequest(
                id,
                Origin.LOCAL,
                doc.conch(),
                DevTraceStore.MCP_REFRESH_ACTION,
                Map.of(),
                ready.account(),
                new MappedAccount(ready.secrets(), ready.config()));
        return McpRefresh.apply(mcpForwarder, localConches, registry, doc, request, actor, clock.instant());
    }

    /** Official packages fail even when the debug switch is off. */
    private ConchDoc requireLocalDebug(String conch) {
        ConchDoc doc = registry.find(conch).orElse(null);
        if (doc == null) {
            throw new TrialRejected(404, ErrorCode.NOT_FOUND, TRIAL_NOT_FOUND, true);
        }
        if (registry.origin(conch) != Origin.LOCAL) {
            throw new TrialRejected(409, ErrorCode.CONFLICT, "先在本地规则包上开发，再导出上传。", true);
        }
        if (!debug.get().enabled()) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, "先打开开发者模式。", true);
        }
        return doc;
    }

    private ReadyAccount openAccount(ConchDoc doc, Optional<String> account) {
        Account resolved;
        try {
            resolved = accounts.resolve(Origin.LOCAL, doc.conch(), account == null ? Optional.empty() : account);
        } catch (AccountNotFoundException ex) {
            if (ex.account() == null && accounts.list(Origin.LOCAL, doc.conch()).isEmpty()) {
                throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, CredentialMessages.NO_ACCOUNT, true);
            }
            throw new TrialRejected(404, ErrorCode.ACCOUNT_NOT_FOUND, Invoker.ACCOUNT_NOT_FOUND_MESSAGE, true);
        } catch (IllegalArgumentException ex) {
            throw new TrialRejected(400, ErrorCode.INVALID_INPUT, TRIAL_ACCOUNT_INVALID, true);
        }
        Map<String, String> stored = conchConfig.all(Origin.LOCAL, doc.conch());
        List<String> missingConfig = DeclaredSettings.missingConfig(doc, stored);
        if (!missingConfig.isEmpty()) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, Invoker.missingConfig(missingConfig), true);
        }
        List<String> missingCredentials = DeclaredSettings.missingCredentials(
                doc, vault.fieldNames(Origin.LOCAL, doc.conch(), resolved.account()));
        if (!missingCredentials.isEmpty()) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, CredentialMessages.missing(
                    resolved.account(), missingCredentials, doc.credentials()), true);
        }
        Map<String, String> secrets;
        try {
            secrets = vault.decryptForExecution(Origin.LOCAL, doc.conch(), resolved.account());
        } catch (CredentialDecryptException ex) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, CredentialDecryptException.MESSAGE, true);
        }
        return new ReadyAccount(resolved.account(), secrets, DeclaredSettings.mergedConfig(doc, stored));
    }

    private static ExecutionResult httpView(ExecutionResult result) {
        if (!(result.data() instanceof McpCallPayload payload)) {
            return result;
        }
        return new ExecutionResult(
                result.success(),
                payload.httpData(),
                result.error(),
                result.durationMillis(),
                result.stepLogs(),
                result.conchVersion(),
                result.traceId());
    }

    private record ReadyAccount(String account, Map<String, String> secrets, Map<String, String> config) {
    }

    public ConsolePassword consolePassword() {
        return consolePassword;
    }

    public ConsoleSessions consoleSessions() {
        return consoleSessions;
    }

    /** Replaces the hash and drops every in-memory console session in this process. */
    public void replaceConsolePassword(String next) {
        consolePassword.set(next);
        consoleSessions.clear();
    }

    public void recordConsoleLogin(boolean accepted) {
        AuditLog.record(
                database.dataSource(),
                Actors.CONSOLE,
                AuditKind.CONSOLE_LOGIN,
                "console",
                accepted ? "{\"ok\":true}" : "{\"ok\":false}");
    }

    public EngineSettings settings() {
        return settings;
    }

    /**
     * Parses the Reef key and, when {@code reef.url} is set, builds sync and report objects.
     * Safe to call more than once.
     */
    public void prepare() {
        if (!prepared.compareAndSet(false, true)) {
            return;
        }
        this.reefKey = ReefPublicKeys.read(config.reefPublicKey(), paths.root());
        ReportSettingsStore.Saved saved = new ReportSettingsStore(database.dataSource()).read().orElse(null);
        if (saved != null) {
            reportEnabled = saved.enabled();
            reportIntervalSeconds = saved.intervalSeconds();
        }
        if (config.reefToken() != null && !config.reefToken().isBlank()) {
            knownSecrets.add(config.reefToken());
        }
        if (config.reefUrl() == null) {
            return;
        }
        if (reefKey == null) {
            throw new IllegalStateException("reef.url is set but reef.public_key is missing");
        }
        URI reefUrl = reefUri(config.reefUrl());
        UUID instance = UUID.fromString(instanceId);
        Optional<String> token = Optional.ofNullable(config.reefToken()).filter(value -> !value.isBlank());
        attachReef(reefUrl, token, reefKey, Duration.ofSeconds(config.syncIntervalSeconds()), instance);
    }

    public LoadReport loadInstalledConches() {
        prepare();
        if (reefKey == null) {
            if (hasInstalledPointer()) {
                throw new IllegalStateException("reef.public_key is required to verify installed conches");
            }
            localConches.loadStartup();
            return new LoadReport(List.of(), List.of());
        }
        paths.conches();
        LoadReport official = InstalledConches.loadAll(paths, reefKey, registry);
        localConches.loadStartup();
        return official;
    }

    public LocalConches localConches() {
        return localConches;
    }

    public javax.sql.DataSource dataSource() {
        return database.dataSource();
    }

    public ConchSync conchSync() {
        return conchSync;
    }

    /**
     * 装在本机、但当前校验不让载入的包（正式包或本地包）。不在注册表里，调用、测试登录都不能用，
     * 但它不是「不存在」：控制台和外部调用要明说已停用。
     */
    public boolean ruleDisabled(String conch) {
        if (conch == null || registry.find(conch).isPresent()) {
            return false;
        }
        LocalConches.LocalPackage local = localPackage(conch);
        if (local != null) {
            return local.ruleDisabled();
        }
        ConchSync sync = conchSync;
        return sync != null && sync.disabledRule(conch).isPresent();
    }

    /** 本地包被停用时下一步是改规则重新导入；正式包是更新或退回。 */
    public boolean localRuleDisabled(String conch) {
        LocalConches.LocalPackage local = localPackage(conch);
        return local != null && local.ruleDisabled();
    }

    /** {@link #ruleDisabled} 为 true 的全部包名。 */
    public java.util.Set<String> ruleDisabledNames() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        if (localConches != null) {
            for (LocalConches.LocalPackage pkg : localConches.packages()) {
                if (pkg.ruleDisabled() && pkg.conch() != null) {
                    names.add(pkg.conch());
                }
            }
        }
        ConchSync sync = conchSync;
        if (sync != null) {
            for (ConchSync.DisabledRule rule : sync.disabledRules()) {
                names.add(rule.conch());
            }
        }
        return names;
    }

    private LocalConches.LocalPackage localPackage(String conch) {
        if (localConches == null) {
            return null;
        }
        for (LocalConches.LocalPackage pkg : localConches.packages()) {
            if (conch.equals(pkg.conch())) {
                return pkg;
            }
        }
        return null;
    }

    public ReportSender reportSender() {
        return reportSender;
    }

    public boolean reportsConfigured() {
        return reportEnabled && reportSender != null;
    }

    public Map<String, Object> reefStatus() {
        prepare();
        return reefView(ReefBootstrap.readStored(paths).orElse(null));
    }

    /**
     * Validates the bootstrap file, checks the token against Reef, then writes it and applies
     * the new URL, token, and public key in this process. A sync failure after the write does
     * not undo the import.
     */
    public Map<String, Object> importBootstrap(byte[] body, String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        if (body == null) {
            throw new ReefBootstrapException(400, ErrorCode.INVALID_INPUT, "invalid bootstrap");
        }
        if (body.length > ReefBootstrap.MAX_BYTES) {
            throw new ReefBootstrapException(400, ErrorCode.INVALID_INPUT, "bootstrap is larger than 64 KiB");
        }
        ReefBootstrap.Document document = ReefBootstrap.parseImport(body);
        ReefBootstrap.probe(document, UUID.fromString(instanceId));
        prepare();
        if (hasInstalledPointer() && !ReefBootstrap.sameKey(reefKey, document.publicKey())) {
            throw new ReefBootstrapException(409, ErrorCode.CONFLICT, ReefBootstrap.KEY_CONFLICT);
        }
        ReefBootstrap.write(paths, document, clock.instant());
        knownSecrets.add(document.token());
        applyImportedReef(document);
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("url", document.url());
        if (document.owner() != null) {
            detail.put("owner", document.owner());
        }
        if (document.tokenName() != null) {
            detail.put("tokenName", document.tokenName());
        }
        String prefix = ReefBootstrap.tokenPrefix(document.token());
        if (prefix != null) {
            detail.put("tokenPrefix", prefix);
        }
        AuditLog.record(
                database.dataSource(), actor, AuditKind.REEF_BOOTSTRAPPED, "reef", ApiJson.write(detail));
        try {
            syncOnce(null);
        } catch (RuntimeException ex) {
            LOG.warning("bootstrap sync failed: " + ex.getClass().getName());
        }
        if (serving.get()) {
            synchronized (lifecycle) {
                ensureSyncLoop(false);
                ensureReportLoops();
            }
        }
        return reefStatus();
    }

    public Map<String, Object> reportSettings() {
        prepare();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", reportEnabled);
        body.put("intervalSeconds", reportIntervalSeconds);
        return body;
    }

    public Map<String, Object> updateReportSettings(boolean enabled, int intervalSeconds, String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        if (intervalSeconds < 60) {
            throw new IllegalArgumentException("report.interval_seconds must be at least 60");
        }
        prepare();
        new ReportSettingsStore(database.dataSource()).write(enabled, intervalSeconds, clock.instant());
        this.reportEnabled = enabled;
        this.reportIntervalSeconds = intervalSeconds;
        LiveReportSettings live = liveReport;
        if (live != null) {
            ReportSettings current = live.get();
            live.set(new ReportSettings(
                    enabled, current.reefUrl(), current.token(), current.instanceId(), current.crabVersion()));
        }
        if (serving.get()) {
            synchronized (lifecycle) {
                rescheduleReportSend();
            }
        }
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("enabled", enabled);
        detail.put("intervalSeconds", intervalSeconds);
        AuditLog.record(
                database.dataSource(),
                actor,
                AuditKind.REPORT_SETTINGS_CHANGED,
                "report",
                ApiJson.write(detail));
        return reportSettings();
    }

    public Optional<SyncReport> lastSync() {
        return Optional.ofNullable(lastSync);
    }

    public boolean subscribed(String conch) {
        return subscriptions.contains(conch);
    }

    public ConchCatalog catalog() {
        prepare();
        if (conchSync == null) {
            return new ConchCatalog(false, null, List.of());
        }
        return conchSync.catalog();
    }

    /**
     * Subscribes and syncs. One {@code SYNC_REQUESTED} audit is written after the fresh
     * manifest check and the install sync both succeed.
     */
    public ConchOutcome subscribe(String conch, String actor) {
        prepare();
        if (localConches.occupies(conch) && registry.origin(conch) != Origin.LOCAL) {
            throw new SubscriptionException(409, ErrorCode.INVALID_INPUT, SubscriptionException.LOCAL_CONFLICT);
        }
        if (conchSync == null) {
            throw new SubscriptionException(
                    409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
        }
        ConchOutcome outcome = conchSync.subscribe(conch, actor, this::recordCredentialChanges);
        AuditLog.record(database.dataSource(), actor, AuditKind.SYNC_REQUESTED, "sync", "{}");
        return outcome;
    }

    /**
     * Installs the last synced catalog version when it is higher than the installed version.
     * The caller syncs first; this method does not refetch the manifest.
     */
    public ConchOutcome update(String conch, String actor) {
        prepare();
        if (conchSync == null) {
            throw new SubscriptionException(
                    409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
        }
        UpdateResult result = conchSync.update(conch, actor);
        result.credentialChange().ifPresent(change -> recordCredentialChange(change, Actors.SYNC));
        if (result.fromVersion() != null && result.toVersion() != null && reportQueue != null) {
            try {
                reportQueue.enqueueVersionChange(
                        conch,
                        result.fromVersion(),
                        result.toVersion(),
                        ReportQueue.MANUAL_UPDATE,
                        clock.instant());
            } catch (RuntimeException ex) {
                LOG.warning("version change report failed: " + ex.getClass().getName());
            }
        }
        return result.outcome();
    }

    /**
     * Installs the one previous version kept after the last switch. Does not report the change to Reef.
     */
    public ConchOutcome rollback(String conch, String actor) {
        prepare();
        if (conchSync == null) {
            throw new SubscriptionException(
                    409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
        }
        UpdateResult result = conchSync.rollback(conch, actor);
        result.credentialChange().ifPresent(change -> recordCredentialChange(change, Actors.SYNC));
        return result.outcome();
    }

    public String previousVersion(String conch) {
        return previousState(conch).version();
    }

    public ConchSync.PreviousState previousState(String conch) {
        if (conchSync == null) {
            return ConchSync.PreviousState.none();
        }
        return conchSync.previousState(conch);
    }

    public void unsubscribe(String conch, String actor) {
        prepare();
        if (conchSync != null) {
            conchSync.unsubscribe(conch, actor);
            return;
        }
        SubscriptionCommands.unsubscribe(
                null, paths, registry, disabledConches, subscriptions, database.dataSource(), conch, actor);
    }

    public Optional<SyncReport> syncOnce(String requestedBy) {
        prepare();
        if (conchSync == null) {
            return Optional.empty();
        }
        SyncReport report = conchSync.syncOnce();
        if (report == null) {
            return Optional.empty();
        }
        recordCredentialChanges(report);
        if (requestedBy != null) {
            AuditLog.record(database.dataSource(), requestedBy, AuditKind.SYNC_REQUESTED, "sync", "{}");
        }
        return Optional.of(report);
    }

    void syncQuietly() {
        try {
            syncOnce(null);
        } catch (Exception ex) {
            LOG.warning("conch sync failed: " + ex.getClass().getName());
        }
    }

    void flushQuietly() {
        ReportSender sender = reportSender;
        if (sender == null) {
            return;
        }
        try {
            sender.flushOnce();
        } catch (Exception ex) {
            LOG.warning("report flush failed: " + ex.getClass().getName());
        }
    }

    public Optional<Action> enabledAction(String conch, String action) {
        ConchDoc doc = registry.find(conch).orElse(null);
        if (doc == null) {
            return Optional.empty();
        }
        Action found = doc.actions().get(action);
        if (found == null || disabled(conch, action)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public boolean disabled(String conch, String action) {
        String key = conch + "." + action;
        return configDisabled.contains(key) || switchDisabled.contains(key);
    }

    public void setCapabilityDisabled(String conch, String action, boolean disabled, String actor) {
        ConchDoc doc = registry.find(conch).orElse(null);
        if (doc == null || !doc.actions().containsKey(action)) {
            throw new IllegalArgumentException("capability not found");
        }
        Origin stored = registry.origin(conch);
        switches.setDisabled(stored == null ? Origin.OFFICIAL : stored, conch, action, disabled, actor);
        String key = conch + "." + action;
        if (disabled) {
            switchDisabled.add(key);
        } else {
            switchDisabled.remove(key);
        }
        notifyCatalogListeners();
    }

    /** MCP tool-list notifications when a capability switch changes. */
    public void addCatalogListener(Runnable listener) {
        if (listener != null) {
            catalogListeners.add(listener);
        }
    }

    private void onDebugChanged(boolean enabled, java.util.List<String> deleted) {
        traceMemory.rememberDeleted(deleted);
        if (!enabled && localConches != null) {
            localConches.clearGrants();
        }
        notifyCatalogListeners();
    }

    private void notifyCatalogListeners() {
        for (Runnable listener : catalogListeners) {
            try {
                listener.run();
            } catch (RuntimeException ex) {
                LOG.warning("catalog listener failed: " + ex.getClass().getName());
            }
        }
    }

    public void addListener(ExecutionListener listener) {
        invoker.addListener(listener);
    }

    public void startBackground() {
        purgeExpired();
        synchronized (lifecycle) {
            if (scheduler == null) {
                scheduler = scheduler("hermit-crab-retention");
                scheduler.scheduleWithFixedDelay(this::safePurge, 1, 1, TimeUnit.HOURS);
            }
        }
    }

    /** Starts retention, sync, and report loops. Safe to call once after HTTP is listening. */
    public void startServing() {
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
        prepare();
        synchronized (lifecycle) {
            if (!serving.compareAndSet(false, true)) {
                return;
            }
            startBackground();
            ensureSyncLoop(true);
            ensureReportLoops();
        }
    }

    /** Stops background loops without closing the database. Used when server startup fails. */
    void abandonServing() {
        synchronized (lifecycle) {
            serving.set(false);
            ScheduledFuture<?> task = reportSendTask;
            if (task != null) {
                task.cancel(false);
            }
            reportSendTask = null;
            stopScheduler(reportScheduler);
            reportScheduler = null;
            stopScheduler(syncScheduler);
            syncScheduler = null;
            stopScheduler(memoryScheduler);
            memoryScheduler = null;
            stopScheduler(scheduler);
            scheduler = null;
        }
    }

    public void purgeExpired() {
        callLogs.purgeBefore(clock.instant().minus(CALL_LOG_RETENTION));
        idempotency.purgeBefore(clock.instant().minus(IdempotencyStore.TTL));
        debug.expire();
        if (debug.get().enabled()) {
            devTraces.deleteExpired(clock.instant());
        } else {
            devTraces.deleteAll();
        }
    }

    private void logTrial(
            ExecutionResult result,
            Map<String, String> secrets,
            String conch,
            String action,
            String account,
            String apiKeyId
    ) {
        String message = null;
        String code = null;
        if (!result.success() && result.error() != null) {
            code = result.error().code().name();
            LinkedHashSet<String> values = new LinkedHashSet<>(knownSecrets.copy());
            if (secrets != null) {
                for (String value : secrets.values()) {
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
            message = Redactor.of(values).redact(result.error().message());
        }
        try {
            callLogs.append(new CallLogStore.NewCall(
                    result.traceId() == null ? "" : result.traceId(),
                    Origin.LOCAL,
                    conch,
                    action,
                    account,
                    result.conchVersion(),
                    result.success(),
                    code,
                    message,
                    result.durationMillis(),
                    apiKeyId,
                    CallChannel.CONSOLE), result.stepLogs());
        } catch (RuntimeException ex) {
            LOG.warning("call log failed: " + ex.getClass().getName());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            abandonServing();
            ReportQueue queue = reportQueue;
            if (queue != null) {
                try {
                    queue.flushMemory();
                } catch (RuntimeException ex) {
                    LOG.warning("report memory flush failed: " + ex.getClass().getName());
                }
            }
            mcpForwarder.close();
            sessions.close();
            database.close();
        } finally {
            tlsTrust.close();
            OutboundTls.restore(previousTls);
        }
    }

    private void safePurge() {
        try {
            purgeExpired();
        } catch (RuntimeException ex) {
            LOG.warning("retention failed: " + ex.getClass().getName());
        }
    }

    private void rememberCompletedSync(SyncReport report) {
        if (report != null) {
            lastSync = report;
        }
    }

    private void recordCredentialChanges(SyncReport report) {
        for (CredentialDeclarationChange change : report.credentialChanges()) {
            recordCredentialChange(change, Actors.SYNC);
        }
    }

    private void recordCredentialChange(CredentialDeclarationChange change, String actor) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("addedRequired", new ArrayList<>(new TreeSet<>(change.addedRequired())));
        detail.put("removed", new ArrayList<>(new TreeSet<>(change.removed())));
        AuditLog.record(
                database.dataSource(),
                actor,
                AuditKind.CREDENTIAL_DECLARATION,
                change.conch(),
                ApiJson.write(detail));
    }

    private boolean hasInstalledPointer() {
        Path root = paths.conches();
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (Files.isRegularFile(child.resolve("current"))) {
                    return true;
                }
            }
            return false;
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot list installed conches", ex);
        }
    }

    private void attachReef(URI reefUrl, Optional<String> token, PublicKey key, Duration interval, UUID instance) {
        SyncSettings syncSettings = new SyncSettings(reefUrl, token, key, interval, instance);
        ConchSync sync = new ConchSync(
                syncSettings, paths, registry, vault, database.dataSource(), clock, disabledConches);
        sync.onReport(this::rememberCompletedSync);
        this.conchSync = sync;
        admin.bindStagedDeclarations(sync::stagedDeclaration);
        ReportSettings reportSettings = new ReportSettings(
                reportEnabled, reefUrl, token, instance, CrabVersion.CURRENT.toString());
        this.liveReport = new LiveReportSettings(reportSettings);
        this.reportQueue = new ReportQueue(liveReport, database.dataSource(), clock);
        invoker.bindHooks(reportQueue);
        invoker.bindVersionSuccess(database.dataSource(), clock);
        this.reportSender = new ReportSender(liveReport, reportQueue, clock);
        this.reefKey = key;
    }

    private void applyImportedReef(ReefBootstrap.Document document) {
        URI reefUrl = reefUri(document.url());
        UUID instance = UUID.fromString(instanceId);
        Optional<String> token = Optional.of(document.token());
        if (conchSync == null) {
            attachReef(reefUrl, token, document.publicKey(), Duration.ofSeconds(config.syncIntervalSeconds()), instance);
            return;
        }
        SyncSettings current = conchSync.currentSettings();
        conchSync.retarget(new SyncSettings(
                reefUrl, token, document.publicKey(), current.interval(), current.instanceId()));
        this.reefKey = document.publicKey();
        ReportSettings next = new ReportSettings(
                reportEnabled, reefUrl, token, instance, CrabVersion.CURRENT.toString());
        if (liveReport == null) {
            this.liveReport = new LiveReportSettings(next);
            this.reportQueue = new ReportQueue(liveReport, database.dataSource(), clock);
            invoker.bindHooks(reportQueue);
            invoker.bindVersionSuccess(database.dataSource(), clock);
            this.reportSender = new ReportSender(liveReport, reportQueue, clock);
        } else {
            liveReport.set(next);
            if (reportSender == null) {
                this.reportSender = new ReportSender(liveReport, reportQueue, clock);
            }
        }
    }

    private Map<String, Object> reefView(ReefBootstrap.Document stored) {
        String url = null;
        String token = null;
        PublicKey key = reefKey;
        String source = null;
        if (conchSync != null) {
            SyncSettings current = conchSync.currentSettings();
            url = current.reefUrl().toString();
            token = current.token().orElse(null);
            key = current.reefKey();
            source = stored == null ? "CONFIG" : "BOOTSTRAP";
        } else if (config.reefUrl() != null) {
            url = config.reefUrl();
            token = config.reefToken();
            source = stored == null ? "CONFIG" : "BOOTSTRAP";
        }
        if (stored != null && "BOOTSTRAP".equals(source)) {
            url = stored.url();
            if (token == null) {
                token = stored.token();
            }
            if (key == null) {
                key = stored.publicKey();
            }
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("configured", url != null);
        body.put("source", source);
        body.put("url", url);
        body.put("owner", stored == null ? null : stored.owner());
        body.put("tokenName", stored == null ? null : stored.tokenName());
        body.put("tokenPrefix", ReefBootstrap.tokenPrefix(token));
        body.put("publicKeyFingerprint", ReefBootstrap.fingerprint(key));
        body.put("instanceId", instanceId);
        body.put("importedAt", stored == null ? null : stored.importedAt());
        return body;
    }

    private void ensureSyncLoop(boolean immediate) {
        if (conchSync == null || closed.get()) {
            return;
        }
        if (syncScheduler == null) {
            syncScheduler = scheduler("hermit-crab-sync");
            conchSync.start(syncScheduler);
        }
        if (immediate) {
            syncScheduler.execute(this::syncQuietly);
        }
    }

    private void ensureReportLoops() {
        if (closed.get()) {
            return;
        }
        if (reportQueue != null && memoryScheduler == null) {
            memoryScheduler = scheduler("hermit-crab-report-memory");
            long seconds = ReportQueue.MEMORY_FLUSH_INTERVAL.toSeconds();
            memoryScheduler.scheduleWithFixedDelay(this::flushMemoryQuietly, seconds, seconds, TimeUnit.SECONDS);
        }
        rescheduleReportSend();
    }

    private void rescheduleReportSend() {
        if (!serving.get() || reportSender == null || closed.get()) {
            return;
        }
        if (reportScheduler == null) {
            reportScheduler = scheduler("hermit-crab-report");
        }
        ScheduledFuture<?> previous = reportSendTask;
        if (previous != null) {
            previous.cancel(false);
        }
        long seconds = Math.max(60L, reportIntervalSeconds);
        reportSendTask = reportScheduler.scheduleWithFixedDelay(this::flushQuietly, seconds, seconds, TimeUnit.SECONDS);
    }

    private void flushMemoryQuietly() {
        ReportQueue queue = reportQueue;
        if (queue == null) {
            return;
        }
        try {
            queue.flushMemory();
        } catch (RuntimeException ex) {
            LOG.warning("report memory flush failed: " + ex.getClass().getName());
        }
    }

    private static ScheduledExecutorService scheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void stopScheduler(ScheduledExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static URI reefUri(String text) {
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("reef.url is not a usable http(s) address");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException("reef.url is not a usable http(s) address");
        }
        return uri;
    }
}
