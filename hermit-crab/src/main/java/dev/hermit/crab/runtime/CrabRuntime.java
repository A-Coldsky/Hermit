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
import java.util.Collections;
import java.util.HashSet;
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
import java.util.TreeMap;
import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.crab.engine.SessionContention;
import dev.hermit.crab.upstream.McpCallPayload;
import dev.hermit.crab.upstream.McpForwarder;
import dev.hermit.crab.upstream.McpOAuth;
import dev.hermit.crab.upstream.McpRefresh;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.FileStageSettings;
import dev.hermit.crab.files.FileStageSettingsStore;
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
import dev.hermit.crab.store.ApiKeyPrincipal;
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
import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.store.EncryptedSessionPersistence;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.common.validate.AuthenticatorChecks;
import dev.hermit.crab.store.AuthBinding;
import dev.hermit.crab.store.AuthBindingStore;
import dev.hermit.crab.store.Identity;
import dev.hermit.crab.store.IdentityStore;
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
    private static final Duration CONFIG_AUDIT_RETENTION = Duration.ofDays(180);

    private final CrabPaths paths;
    private final CrabConfig config;
    private final Clock clock;
    private final CrabDatabase database;
    private final CredentialCipher cipher;
    private final AccountStore accounts;
    private final dev.hermit.crab.store.ConchSourceStore conchSources;
    private final CredentialVault vault;
    private final ConchConfigStore conchConfig;
    private final SessionContention contention;
    private final ApiKeyService apiKeys;
    private final ConchRegistry registry = new ConchRegistry();
    private final EngineSettings settings;
    private final SessionManager sessions;
    private final Identities identities;
    private final Authenticators authenticators;
    private final RuleEngine engine;
    private final McpForwarder mcpForwarder;
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilitySwitchStore switches;
    private final dev.hermit.crab.store.ConchSwitchStore conchSwitches;
    private final Set<String> conchSwitchedOff = ConcurrentHashMap.newKeySet();
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
    private final Object switchLock = new Object();
    private volatile PublicKey reefKey;
    private ConchSync conchSync;
    private LiveReportSettings liveReport;
    private ReportQueue reportQueue;
    private ReportSender reportSender;
    private volatile boolean reportEnabled;
    private volatile int reportIntervalSeconds;
    /** Last completed sync, from startup, the timer, or a manual request. Sync threads write; HTTP threads read. */
    private volatile SyncReport lastSync;
    private final FileStage fileStage;
    private final dev.hermit.crab.recorder.RecorderManager recorder;
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
        this.accounts = new AccountStore(database.dataSource(), clock);
        this.vault = new CredentialVault(database.dataSource(), cipher, clock);
        if (this.vault.anyUnreadable()) {
            LOG.warning(CredentialDecryptException.MESSAGE + "。主密钥与已保存的凭证不匹配，请重新配置账号凭证。");
        }
        this.conchConfig = new ConchConfigStore(database.dataSource(), clock);
        this.apiKeys = new ApiKeyService(database.dataSource(), clock);
        this.settings = config.engineSettings();
        IdentityStore identityStore = new IdentityStore(database.dataSource(), cipher, clock);
        AuthBindingStore authBindings = new AuthBindingStore(database.dataSource(), clock);
        EncryptedSessionPersistence sessionPersistence = new EncryptedSessionPersistence(database.dataSource(), cipher);
        this.sessions = new SessionManager(
                sessionPersistence,
                new EncryptedLoginSnapshotStore(database.dataSource(), cipher),
                Authenticators.shared(identityStore, authBindings, registry, sessionPersistence),
                settings,
                clock);
        this.identities = new Identities(identityStore, sessions, registry, conchConfig, accounts, vault);
        this.authenticators = new Authenticators(
                authBindings, sessions, registry, conchConfig, accounts, vault, identities,
                this::conchDisabled);
        this.identities.bindAuthenticators(authenticators);
        this.engine = new RuleEngine(registry, sessions, settings, knownSecrets);
        this.engine.useAuthBindings(authenticators);
        this.contention = new SessionContention(clock);
        this.engine.bindContention(contention);
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
        this.switches = new CapabilitySwitchStore(database.dataSource(), clock);
        this.conchSwitches = new dev.hermit.crab.store.ConchSwitchStore(database.dataSource(), clock);
        dev.hermit.crab.store.ConchSourceStore sources = new dev.hermit.crab.store.ConchSourceStore(database.dataSource(), clock);
        this.conchSources = sources;
        this.registry.bindSelections(new ConchRegistry.Selections() {
            @Override
            public Map<String, Origin> load() {
                return sources.load();
            }

            @Override
            public void save(String conch, Origin origin) {
                sources.save(conch, origin);
            }
        });
        this.conchSwitchedOff.addAll(conchSwitches.disabledNames());
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
        this.invoker.bindAuthenticatorCheck(this::isKnownAuthenticator);
        this.invoker.bindSwitchedOff(this::conchSwitchedOff);
        this.invoker.bindRivals(this::contendingWith);
        this.fileStage = new FileStage(
                paths.data().resolve("files"),
                clock,
                new FileStageSettingsStore(database.dataSource()).read().orElse(FileStageSettings.defaults()));
        this.recorder = new dev.hermit.crab.recorder.RecorderManager(
                paths.data().resolve("chrome-recorder"),
                clock,
                environment,
                () -> debug.get().enabled());
        this.invoker.bindFileStage(fileStage, fileBase());
        this.admin = new ConchAdmin(
                database.dataSource(), accounts, vault, conchConfig, apiKeys, sessions, engine, registry, disabledConches, clock);
        this.admin.bindAuthenticatorCheck(this::isKnownAuthenticator);
        this.localConches = new LocalConches(
                paths, registry.scoped(Origin.LOCAL), database.dataSource(), () -> debug.get().enabled(), clock);
        this.localConches.onWidened(this::clearWidenedSessions);
        this.localConches.onDeleted((name, actor) -> identities.packageRemoved(Origin.LOCAL, name, actor));
        this.localConches.onBoundCheck(conch -> identities.hasBoundLocal(conch));
        this.admin.bindOriginFallback(name -> localConches.occupies(name) ? Origin.LOCAL : null);
        this.admin.bindAccountRemoved(mcpForwarder::forgetAccount);
        this.admin.bindIdentities(identities);
        // 包换了版本、切了来源：和身份里其它成员不再有相同地址的账号离开身份。
        this.registry.addListener(() -> identities.revalidate(Actors.SYNC));
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
            this.recorder.close();
            tlsTrust.close();
            this.mcpForwarder.close();
            this.database.close();
            throw ex;
        }
    }

    public dev.hermit.crab.recorder.RecorderManager recorder() {
        return recorder;
    }

    public TlsTrust tlsTrust() {
        return tlsTrust;
    }

    /** 检测只允许已加载规则的 base_url、认证地址、MCP 上游和 OAuth 端点，以及 Reef 地址。 */
    private boolean probeAllowed(String host, int port) {
        return probeTargets().contains(host + ":" + port);
    }

    private Set<String> probeTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        // 授权器的认证地址同样要连，证书也要能检测和信任。
        for (String name : registry.installed().keySet()) {
            ConchRegistry.Active active = registry.installed(name).orElse(null);
            if (active == null) {
                continue;
            }
            ConchDoc doc = active.doc();
            addProbeTarget(targets, doc.baseUrl());
            // 登录步骤也会连认证地址，那边的证书同样要能检测和信任（包括已停用包的配置项认证地址）。
            for (String origin : AuthOrigins.declared(doc)) {
                addProbeTarget(targets, origin);
            }
            if (AuthOrigins.used(doc)) {
                Map<String, String> merged = DeclaredSettings.mergedConfig(doc, conchConfig.all(active.origin(), name));
                for (String origin : AuthOrigins.effective(doc, merged)) {
                    addProbeTarget(targets, origin);
                }
            }
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
        sessions.pending().discardConch(Origin.LOCAL, conch);
        // 先离开身份：新地址不该拿到别的包登录出来的 Cookie，也不该因为它清掉别的成员的会话。
        identities.widened(conch);
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

    public Identities identities() {
        return identities;
    }

    private boolean conchDisabled(String conch) {
        return disabledConches.contains(conch) || conchSwitchedOff.contains(conch);
    }

    public Authenticators authenticators() {
        return authenticators;
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

    /** Records a trial that was refused before execution, so a later hermit_dev__debug_trace can explain it. */
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
     * {@code apiKeyId} is recorded only for {@code hermit_dev__trial_run}; console and CLI trials pass null.
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
        return trialLocal(conch, action, account, input, traceId, apiKeyId, confirmedAgainst, null, confirmedAgainst != null);
    }

    /**
     * 原子读出当前生效的本地规则包，核对快照一致、API Key 范围与只读权限，以及破坏性确认。
     * {@code principal} 为 null 时表示控制台或 CLI 试跑，不检查 API Key 权限与 MCP 确认。
     */
    public ExecutionResult trialLocal(
            String conch,
            String action,
            Optional<String> account,
            Map<String, Object> input,
            String traceId,
            String apiKeyId,
            ConchDoc expectedSnapshot,
            ApiKeyPrincipal principal,
            boolean confirmed
    ) {
        String id = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        ConchDoc doc = requireLocalDebug(conch);
        if (expectedSnapshot != null && !Invoker.sameSnapshot(doc, expectedSnapshot)) {
            throw new TrialRejected(409, ErrorCode.CONFLICT, Invoker.CHANGED_AFTER_CONFIRM, true);
        }
        if (!"http".equals(doc.driver()) && !"mcp".equals(doc.driver())) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, "只支持 HTTP 或 MCP 规则包的试跑。", true);
        }
        Action capability = doc.actions().get(action);
        if (capability == null) {
            throw new TrialRejected(404, ErrorCode.NOT_FOUND, TRIAL_NOT_FOUND, true);
        }
        if (principal != null) {
            boolean readOnly = CapabilityHttp.readOnly(capability);
            if (!principal.scope().allowsLocal(conch)) {
                throw new TrialRejected(403, ErrorCode.FORBIDDEN,
                        "这把 API Key 不能使用本地规则包 " + conch + "。请用全部权限的 Key，或在 conches 里点名这个包的 Key。",
                        true);
            }
            if (principal.scope().readOnlyOnly() && !readOnly) {
                throw new TrialRejected(403, ErrorCode.FORBIDDEN,
                        "这把 API Key 只允许调用只读能力。能力 " + action + " 不是只读，已拒绝。",
                        true);
            }
            if (!principal.permits(conch, action, capability.group(), readOnly)) {
                throw new TrialRejected(403, ErrorCode.FORBIDDEN,
                        "这把 API Key 不能使用本地规则包 " + conch + "。请用全部权限的 Key，或在 conches 里点名这个包的 Key。",
                        true);
            }
            if (!capability.mcp().expose()) {
                throw new TrialRejected(403, ErrorCode.FORBIDDEN,
                        "能力 " + action + " 未对 MCP 暴露，不能通过 MCP 试跑。",
                        true);
            }
            if (capability.effectiveConfirm() && (!confirmed || expectedSnapshot == null)) {
                throw new TrialRejected(409, ErrorCode.CONFLICT, Invoker.CHANGED_AFTER_CONFIRM, true);
            }
        }
        if (conchSwitchedOff(conch)) {
            throw new TrialRejected(409, ErrorCode.CONCH_DISABLED, Invoker.CONCH_SWITCHED_OFF_CALL, true);
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
        ExecutionResult result;
        String fileOwner = apiKeyId == null ? FileStage.CONSOLE_TRIAL_OWNER : apiKeyId;
        FileScope previous = FileScope.begin(fileStage, fileOwner, fileBase());
        try {
            if (!"mcp".equals(doc.driver())) {
                try {
                    request = new ExecutionRequest(
                            request.traceId(), request.origin(), request.conch(), request.action(),
                            Invoker.stageFileInputs(capability, request.rawInput()),
                            request.account(), request.accountContext());
                } catch (dev.hermit.crab.files.FileStageException ex) {
                    throw new TrialRejected(400, ErrorCode.INVALID_INPUT, ex.getMessage(), true);
                }
            }
            result = "mcp".equals(doc.driver())
                    ? mcpForwarder.execute(request, doc)
                    : engine.execute(request, doc);
        } finally {
            FileScope.restore(previous);
        }
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
    public static final String LOCAL_INACTIVE = "同名的正式规则包正在生效，本地这份没有生效。先在控制台把来源切到本地。";

    private ConchDoc requireLocalDebug(String conch) {
        ConchRegistry.Active live = registry.active(conch).orElse(null);
        if (live == null) {
            throw new TrialRejected(404, ErrorCode.NOT_FOUND, TRIAL_NOT_FOUND, true);
        }
        if (live.origin() != Origin.LOCAL) {
            if (registry.has(conch, Origin.LOCAL)) {
                throw new TrialRejected(409, ErrorCode.CONFLICT, LOCAL_INACTIVE, true);
            }
            throw new TrialRejected(409, ErrorCode.CONFLICT, "先在本地规则包上开发，再导出上传。", true);
        }
        if (!debug.get().enabled()) {
            throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, "先打开开发者模式。", true);
        }
        return live.doc();
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
        if (AuthenticatorChecks.uses(doc)) {
            String problem = authenticators.bindingProblem(new SessionKey(Origin.LOCAL, doc.conch(), resolved.account()), doc);
            if (problem != null) {
                throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, problem, true);
            }
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

    /** 解析本地规则包的指定账号或默认账号；无任何账号或指定账号不存在时抛 {@link TrialRejected}。 */
    public String resolveLocalAccountName(String conch, Optional<String> account) {
        try {
            return accounts.resolve(Origin.LOCAL, conch, account == null ? Optional.empty() : account).account();
        } catch (AccountNotFoundException ex) {
            if (ex.account() == null && accounts.list(Origin.LOCAL, conch).isEmpty()) {
                throw new TrialRejected(409, ErrorCode.NOT_CONFIGURED, CredentialMessages.NO_ACCOUNT, true);
            }
            throw new TrialRejected(404, ErrorCode.ACCOUNT_NOT_FOUND, Invoker.ACCOUNT_NOT_FOUND_MESSAGE, true);
        } catch (IllegalArgumentException ex) {
            throw new TrialRejected(400, ErrorCode.INVALID_INPUT, TRIAL_ACCOUNT_INVALID, true);
        }
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
                clock.instant(),
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
            reconcileMissingLocalMembers();
            return new LoadReport(List.of(), List.of());
        }
        paths.conches();
        LoadReport official = InstalledConches.loadAll(paths, reefKey, registry.scoped(Origin.OFFICIAL));
        localConches.loadStartup();
        reconcileMissingLocalMembers();
        return official;
    }

    /** 启动载入本地包后，把已离线删除的本地包成员从共享身份和授权器绑定中解绑，避免残留绑定被后续同名导入直接复活。 */
    private void reconcileMissingLocalMembers() {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (Identity identity : identities.list()) {
            for (SessionKey member : identity.members()) {
                if (member.origin() == Origin.LOCAL && isLocalMissingOnDisk(member.conch())) {
                    missing.add(member.conch());
                }
            }
        }
        for (AuthBinding binding : authenticators.list()) {
            if (binding.account().origin() == Origin.LOCAL && isLocalMissingOnDisk(binding.account().conch())) {
                missing.add(binding.account().conch());
            }
            if (binding.authenticator().origin() == Origin.LOCAL
                    && isLocalMissingOnDisk(binding.authenticator().conch())) {
                missing.add(binding.authenticator().conch());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        for (String conch : missing) {
            identities.packageRemoved(Origin.LOCAL, conch, Actors.SYNC);
        }
        identities.revalidate(Actors.SYNC);
    }

    private boolean isLocalMissingOnDisk(String conch) {
        return registry.find(conch, Origin.LOCAL).isEmpty()
                && !localConches.occupies(conch)
                && !localConches.hasYamlFile(conch);
    }

    public LocalConches localConches() {
        return localConches;
    }

    /** 本机是否安装着这份 Reef 正式规则包（已载入，或已订阅且磁盘上仍有验签通过的包）。 */
    public boolean hasOfficialPackage(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        return registry.has(conch, Origin.OFFICIAL) || readOfficialYaml(conch).isPresent();
    }

    /** 只读获取已安装的 Reef 正式规则包原始 YAML，经当前 Reef 公钥验签，绝不修改原包。 */
    public Optional<InstalledConches.VerifiedYaml> readOfficialYaml(String conch) {
        if (conch == null || conch.isBlank()) {
            return Optional.empty();
        }
        PublicKey key = reefKey;
        if (key == null) {
            return Optional.empty();
        }
        if (!registry.has(conch, Origin.OFFICIAL) && !subscribed(conch)) {
            return Optional.empty();
        }
        return InstalledConches.readVerifiedYaml(paths, key, conch);
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
        if (conch == null || registry.installed(conch).isPresent()) {
            return false;
        }
        LocalConches.LocalPackage local = localPackage(conch);
        if (local != null) {
            return local.ruleDisabled();
        }
        ConchSync sync = conchSync;
        return sync != null && sync.disabledRule(conch).isPresent();
    }

    /**
     * 本机已知它是 {@code driver: auth} 授权器（生效、另一来源槽、校验停用或已撤下留档）。
     * 若当前生效的是同名可调用规则包，则按可调用包处理。
     */
    public boolean isKnownAuthenticator(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        if (registry.authenticator(conch).isPresent()) {
            return true;
        }
        if (registry.find(conch).isPresent()) {
            return false;
        }
        ConchDoc localLoaded = registry.find(conch, Origin.LOCAL).orElse(null);
        if (localLoaded != null) {
            return AuthenticatorChecks.isAuthenticator(localLoaded);
        }
        ConchDoc officialLoaded = registry.find(conch, Origin.OFFICIAL).orElse(null);
        if (officialLoaded != null) {
            return AuthenticatorChecks.isAuthenticator(officialLoaded);
        }
        if (localConches != null) {
            ConchDoc localReadable = localConches.readableDoc(conch).orElse(null);
            if (localReadable != null) {
                return AuthenticatorChecks.isAuthenticator(localReadable);
            }
        }
        ConchSync sync = conchSync;
        if (sync != null) {
            ConchDoc officialReadable = sync.readableAnyInstalled(conch).orElse(null);
            if (officialReadable != null) {
                return AuthenticatorChecks.isAuthenticator(officialReadable);
            }
        }
        return false;
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
                database.dataSource(), clock.instant(), actor, AuditKind.REEF_BOOTSTRAPPED, "reef", ApiJson.write(detail));
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

    public FileStage fileStage() {
        return fileStage;
    }

    /** 拿不到请求地址时拼下载链接用的前缀：配置的对外地址，否则监听地址。 */
    public String fileBase() {
        if (config.publicUrl() != null) {
            return config.publicUrl();
        }
        String host = config.serverHost();
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            host = "127.0.0.1";
        }
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + config.serverPort();
    }

    /** 新的有效期只作用于之后存入的文件；上限立即生效，已有文件不会因此被删。 */
    public FileStageSettings updateFileSettings(FileStageSettings settings, String actor) {
        new FileStageSettingsStore(database.dataSource()).write(settings, clock.instant());
        fileStage.updateSettings(settings);
        AuditLog.record(
                database.dataSource(),
                clock.instant(),
                actor,
                AuditKind.FILE_SETTINGS_UPDATED,
                "files",
                "{\"ttlSeconds\":" + settings.ttlSeconds()
                        + ",\"maxFileBytes\":" + settings.maxFileBytes()
                        + ",\"maxTotalBytes\":" + settings.maxTotalBytes() + "}");
        return settings;
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
                clock.instant(),
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
        if (conchSync == null) {
            throw new SubscriptionException(
                    409, ErrorCode.NOT_CONFIGURED, SubscriptionException.NOT_CONFIGURED);
        }
        ConchOutcome outcome = conchSync.subscribe(conch, actor, this::recordCredentialChanges);
        AuditLog.record(database.dataSource(), clock.instant(), actor, AuditKind.SYNC_REQUESTED, "sync", "{}");
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
        if (result.fromVersion() != null && result.toVersion() != null && reportQueue != null
                && !isKnownAuthenticator(conch)) {
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
        } else {
            SubscriptionCommands.unsubscribe(
                    null, paths, registry.scoped(Origin.OFFICIAL), disabledConches, subscriptions, database.dataSource(),
                    conch, actor, clock);
        }
        // 正式包不在了：它的账号离开身份，授权器绑定留着等重新订阅。同名的本地包不受影响。
        identities.packageUnsubscribed(conch, actor);
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
            AuditLog.record(database.dataSource(), clock.instant(), requestedBy, AuditKind.SYNC_REQUESTED, "sync", "{}");
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
        return conchSwitchedOff.contains(conch) || configDisabled.contains(key) || switchDisabled.contains(key);
    }

    /** 只看按能力的开关和 config.yml，不看整包开关。控制台用它显示每个能力自己的状态。 */
    public boolean capabilityDisabled(String conch, String action) {
        String key = conch + "." + action;
        return configDisabled.contains(key) || switchDisabled.contains(key);
    }

    /** 整个规则包是否在本机被停用。按包名，不分来源。 */
    public boolean conchSwitchedOff(String conch) {
        return conch != null && conchSwitchedOff.contains(conch);
    }

    /**
     * 每个已启用、当前生效的 {@code driver: http} 包实际访问的站点（{@code scheme://host:port}）。
     * 配置里填了 {@code base_url} 用它，否则用包里的。同名的本地包和正式包只算生效的那一个。
     */
    public Map<String, String> sites() {
        TreeMap<String, String> sites = new TreeMap<>();
        for (String name : registry.snapshot().keySet()) {
            ConchRegistry.Active active = registry.active(name).orElse(null);
            if (active == null || conchSwitchedOff.contains(name)) {
                continue;
            }
            ConchDoc doc = active.doc();
            Map<String, String> merged = DeclaredSettings.mergedConfig(doc, conchConfig.all(active.origin(), name));
            String site = SiteAddress.effective(doc, merged.get("base_url"));
            if (site != null) {
                sites.put(name, site);
            }
        }
        return sites;
    }

    /**
     * 每个已启用、当前生效的 {@code driver: http} 包登录时还要访问的认证地址（{@code auth.origins}，
     * 配置引用按这台 Crab 填的值），不含它自己的站点。没有认证地址的包不在里面。
     */
    public Map<String, List<String>> authOrigins() {
        TreeMap<String, List<String>> all = new TreeMap<>();
        for (String name : registry.snapshot().keySet()) {
            ConchRegistry.Active active = registry.active(name).orElse(null);
            if (active == null || conchSwitchedOff.contains(name) || !AuthOrigins.used(active.doc())) {
                continue;
            }
            ConchDoc doc = active.doc();
            Map<String, String> merged = DeclaredSettings.mergedConfig(doc, conchConfig.all(active.origin(), name));
            ArrayList<String> origins = new ArrayList<>(AuthOrigins.effective(doc, merged));
            origins.remove(SiteAddress.effective(doc, merged.get("base_url")));
            if (!origins.isEmpty()) {
                all.put(name, List.copyOf(origins));
            }
        }
        return all;
    }

    /** 和这个包访问同一网站的其它已启用规则包，按包名排序。只用来提示。 */
    public List<String> sameSite(String conch) {
        return sameSite(conch, sites());
    }

    public static List<String> sameSite(String conch, Map<String, String> sites) {
        String site = sites.get(conch);
        if (site == null) {
            return List.of();
        }
        ArrayList<String> peers = new ArrayList<>();
        for (Map.Entry<String, String> entry : sites.entrySet()) {
            if (!entry.getKey().equals(conch) && entry.getValue().equals(site)) {
                peers.add(entry.getKey());
            }
        }
        return List.copyOf(peers);
    }

    /** 和这个包经同一个认证地址登录、但不是同一网站的其它已启用规则包，按包名排序。只用来提示。 */
    public List<String> sameAuth(String conch) {
        return sameAuth(conch, sites(), authOrigins());
    }

    /**
     * 两个包的地址（站点加认证地址）有一项相同、站点又不同：一个包的认证地址是另一个包的认证地址，
     * 或者就是另一个包的站点（那个包本身是门户）。
     */
    public static List<String> sameAuth(
            String conch, Map<String, String> sites, Map<String, List<String>> authOrigins) {
        String site = sites.get(conch);
        List<String> mine = authOrigins.getOrDefault(conch, List.of());
        if (site == null && mine.isEmpty()) {
            return List.of();
        }
        TreeSet<String> candidates = new TreeSet<>(sites.keySet());
        candidates.addAll(authOrigins.keySet());
        ArrayList<String> peers = new ArrayList<>();
        for (String other : candidates) {
            if (other.equals(conch)) {
                continue;
            }
            String otherSite = sites.get(other);
            if (site != null && site.equals(otherSite)) {
                continue;
            }
            List<String> theirs = authOrigins.getOrDefault(other, List.of());
            if ((otherSite != null && mine.contains(otherSite))
                    || (site != null && theirs.contains(site))
                    || !Collections.disjoint(mine, theirs)) {
                peers.add(other);
            }
        }
        return List.copyOf(peers);
    }

    /**
     * 可能在互相顶掉登录的规则包。只留两个包现在仍然都访问那个地址的（站点或认证地址）；
     * 同一对包在几个地址上都告警时只留最近的一条。
     */
    public List<SessionContention.Pair> contention() {
        Map<String, String> sites = sites();
        Map<String, List<String>> auth = authOrigins();
        ArrayList<SessionContention.Pair> pairs = new ArrayList<>();
        HashSet<List<String>> seen = new HashSet<>();
        for (SessionContention.Pair pair : contention.active()) {
            if (reaches(pair.first(), pair.site(), sites, auth)
                    && reaches(pair.second(), pair.site(), sites, auth)
                    && seen.add(List.of(pair.first(), pair.second()))) {
                pairs.add(pair);
            }
        }
        return List.copyOf(pairs);
    }

    private static boolean reaches(
            String conch, String address, Map<String, String> sites, Map<String, List<String>> auth) {
        return address.equals(sites.get(conch)) || auth.getOrDefault(conch, List.of()).contains(address);
    }

    private List<String> contendingWith(String conch) {
        TreeSet<String> names = new TreeSet<>();
        for (SessionContention.Pair pair : contention()) {
            if (pair.first().equals(conch)) {
                names.add(pair.second());
            } else if (pair.second().equals(conch)) {
                names.add(pair.first());
            }
        }
        return List.copyOf(names);
    }

    public Set<String> conchSwitchedOffNames() {
        return Set.copyOf(conchSwitchedOff);
    }

    /**
     * 停用要求包已载入。启用不要求：包被退订或删除后，留下的停用标记仍可以清掉。
     * 已经进入执行的调用不会被打断。
     */
    public void setConchDisabled(String conch, boolean disabled, String actor) {
        synchronized (switchLock) {
            // 授权器也能停用：停用后引用它的业务包按没有绑定处理。
            ConchRegistry.Active active = registry.installed(conch).orElse(null);
            boolean loaded = active != null;
            if (disabled ? !loaded : !loaded && !conchSwitchedOff.contains(conch)) {
                throw new IllegalArgumentException("conch not found");
            }
            Origin source = active == null ? Origin.OFFICIAL : active.origin();
            conchSwitches.setDisabled(source, conch, disabled, actor);
            if (disabled) {
                conchSwitchedOff.add(conch);
            } else {
                conchSwitchedOff.remove(conch);
            }
        }
        notifyCatalogListeners();
    }

    /** 切换来源的结果。{@code accounts} 和 {@code configKeys} 是复制过去的账号数和配置项数。 */
    public record SourceSwitch(String conch, Origin origin, boolean copied, int accounts, int configKeys) {
    }

    public static final String SOURCE_MISSING = "这个规则包在本机没有这个来源。";

    /**
     * 同名的本地包和正式包之间切换生效的那份。API Key 范围和整包开关按包名，不随来源变。
     * {@code copy} 只在切到正式包时有效：把本地包的账号、凭证和配置复制到正式包，
     * 只复制正式包声明了的字段，正式包已有的同名值被覆盖。不传 {@code copy} 时两边互不影响。
     */
    public SourceSwitch switchSource(String conch, Origin origin, boolean copy, String actor) {
        if (conch == null || origin == null || !registry.has(conch, origin)) {
            throw new IllegalArgumentException(SOURCE_MISSING);
        }
        int copiedAccounts = 0;
        int copiedConfig = 0;
        boolean copied = copy && origin == Origin.OFFICIAL && registry.has(conch, Origin.LOCAL);
        if (copied) {
            ConchDoc target = registry.find(conch, Origin.OFFICIAL)
                    .orElseThrow(() -> new IllegalArgumentException(SOURCE_MISSING));
            // 先在 LOCAL 锁内把要复制的值全部读出并按正式包的声明校验，释放后再获取 OFFICIAL 锁，避免嵌套锁死锁。
            LinkedHashMap<String, Map<String, String>> secrets = new LinkedHashMap<>();
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            String localDefault = null;
            synchronized (dev.hermit.crab.store.ConchLocks.of(database.dataSource(), Origin.LOCAL, conch)) {
                for (Account account : accounts.list(Origin.LOCAL, conch)) {
                    LinkedHashMap<String, String> fields = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry
                            : vault.decryptForExecution(Origin.LOCAL, conch, account.account()).entrySet()) {
                        // OAuth 令牌由系统写入，不复制；正式包上重新连接一次。
                        if (target.credentials() != null
                                && target.credentials().containsKey(entry.getKey())
                                && !dev.hermit.crab.upstream.McpOAuthToken.FIELD.equals(entry.getKey())) {
                            fields.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (!fields.isEmpty()) {
                        DeclaredSettings.checkCredentialFields(target, fields);
                    }
                    secrets.put(account.account(), fields);
                    if (account.defaultAccount()) {
                        localDefault = account.account();
                    }
                }
                for (Map.Entry<String, String> entry : conchConfig.all(Origin.LOCAL, conch).entrySet()) {
                    if (target.config() != null
                            && target.config().containsKey(entry.getKey())
                            && !entry.getValue().isEmpty()) {
                        DeclaredSettings.checkConfigValue(target.config().get(entry.getKey()), entry.getValue());
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            // 账号、凭证和配置在一个事务里写：中途失败时正式包保持原样。
            String defaultAccount = localDefault;
            copiedAccounts = dev.hermit.crab.store.ConchLocks.atomic(
                    database.dataSource(), Origin.OFFICIAL, conch, () -> {
                        int written = 0;
                        Set<String> existing = new java.util.HashSet<>();
                        for (Account account : accounts.list(Origin.OFFICIAL, conch)) {
                            existing.add(account.account());
                        }
                        boolean hadAccounts = !existing.isEmpty();
                        String initialDefault = null;
                        for (Map.Entry<String, Map<String, String>> entry : secrets.entrySet()) {
                            boolean created = !existing.contains(entry.getKey());
                            if (created) {
                                Account inserted = accounts.create(Origin.OFFICIAL, conch, entry.getKey(), actor);
                                if (inserted.defaultAccount()) {
                                    initialDefault = inserted.account();
                                }
                            }
                            boolean wroteCredentials = !entry.getValue().isEmpty();
                            if (wroteCredentials) {
                                vault.write(Origin.OFFICIAL, conch, entry.getKey(), entry.getValue(), actor);
                            }
                            if (created || wroteCredentials) {
                                written++;
                            }
                        }
                        // 正式包原来没有账号时，默认账号跟本地包一致；原来有账号时不动它的默认账号。
                        if (!hadAccounts && defaultAccount != null && !defaultAccount.equals(initialDefault)) {
                            accounts.setDefault(Origin.OFFICIAL, conch, defaultAccount, actor);
                        }
                        if (!values.isEmpty()) {
                            conchConfig.putAll(Origin.OFFICIAL, conch, values, actor);
                        }
                        return written;
                    });
            copiedConfig = values.size();
            if (copiedConfig > 0) {
                // 先让新 base_url 搬家的账号离开旧共享身份，再清它自己的会话，避免误清旧身份里其它成员的共享会话。
                identities.revalidate(actor);
            }
            for (Map.Entry<String, Map<String, String>> entry : secrets.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    sessions.invalidate(new dev.hermit.crab.engine.SessionKey(Origin.OFFICIAL, conch, entry.getKey()));
                }
            }
        }
        boolean changed = registry.origin(conch) != origin;
        if (!registry.activate(conch, origin)) {
            throw new IllegalArgumentException(SOURCE_MISSING);
        }
        if (changed || copied) {
            conchSources.audit(conch, origin, copied, actor);
        }
        notifyCatalogListeners();
        return new SourceSwitch(conch, origin, copied, copiedAccounts, copiedConfig);
    }

    public void setCapabilityDisabled(String conch, String action, boolean disabled, String actor) {
        synchronized (switchLock) {
            ConchRegistry.Active active = registry.active(conch).orElse(null);
            if (active == null || !active.doc().actions().containsKey(action)) {
                throw new IllegalArgumentException("capability not found");
            }
            Origin source = active.origin();
            switches.setDisabled(source, conch, action, disabled, actor);
            // 开关在内存里按「包名.能力」生效。同名的另一份留着停用记录时，启用要一起清掉，
            // 否则重启后又被那条记录停用。
            Origin other = source == Origin.LOCAL ? Origin.OFFICIAL : Origin.LOCAL;
            if (!disabled && switches.disabledKeys(other).contains(conch + "." + action)) {
                switches.setDisabled(other, conch, action, false, actor);
            }
            String key = conch + "." + action;
            if (disabled) {
                switchDisabled.add(key);
            } else {
                switchDisabled.remove(key);
            }
        }
        notifyCatalogListeners();
    }

    /**
     * 按分组批量启停能力。一级分组同时包含其直属能力与所有下属二级子分组的能力；
     * 底层写入现有能力开关表，不新增数据库表，操作后仍可单独切换某个能力。
     */
    public List<String> setGroupDisabled(String conch, String groupPath, boolean disabled, String actor) {
        List<String> affected;
        synchronized (switchLock) {
            ConchRegistry.Active active = registry.active(conch).orElse(null);
            if (active == null) {
                throw new IllegalArgumentException("conch not found");
            }
            ConchDoc doc = active.doc();
            if (!dev.hermit.common.model.CapabilityGroups.hasGroup(doc, groupPath)) {
                throw new IllegalArgumentException("group not found");
            }
            List<Action> matched = dev.hermit.common.model.CapabilityGroups.actionsInGroup(doc, groupPath);
            ArrayList<String> actionNames = new ArrayList<>(matched.size());
            for (Action action : matched) {
                actionNames.add(action.name());
            }
            if (actionNames.isEmpty()) {
                return List.of();
            }
            Origin source = active.origin();
            switches.setDisabledBatch(source, conch, actionNames, disabled, actor);
            Origin other = source == Origin.LOCAL ? Origin.OFFICIAL : Origin.LOCAL;
            if (!disabled) {
                Set<String> otherDisabled = switches.disabledKeys(other);
                ArrayList<String> clearOther = new ArrayList<>();
                for (String action : actionNames) {
                    if (otherDisabled.contains(conch + "." + action)) {
                        clearOther.add(action);
                    }
                }
                if (!clearOther.isEmpty()) {
                    switches.setDisabledBatch(other, conch, clearOther, false, actor);
                }
            }
            for (String action : actionNames) {
                String key = conch + "." + action;
                if (disabled) {
                    switchDisabled.add(key);
                } else {
                    switchDisabled.remove(key);
                }
            }
            affected = List.copyOf(actionNames);
        }
        notifyCatalogListeners();
        return affected;
    }

    /** MCP tool-list notifications when a capability switch changes. */
    public void addCatalogListener(Runnable listener) {
        if (listener != null) {
            catalogListeners.add(listener);
        }
    }

    private void onDebugChanged(boolean enabled, java.util.List<String> deleted) {
        traceMemory.rememberDeleted(deleted);
        if (!enabled) {
            if (localConches != null) {
                localConches.clearGrants();
            }
            if (recorder != null) {
                recorder.clearAll();
            }
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
        synchronized (lifecycle) {
            if (closed.get()) {
                return;
            }
            purgeExpired();
            if (scheduler == null) {
                scheduler = scheduler("hermit-crab-retention");
                scheduler.scheduleWithFixedDelay(this::safePurge, 1, 1, TimeUnit.HOURS);
                scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        fileStage.sweep();
                    } catch (RuntimeException ex) {
                        LOG.warning("file stage sweep failed: " + ex.getClass().getName());
                    }
                }, 1, 1, TimeUnit.MINUTES);
                scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        sessions.closeIdleConnections(OutboundHttp.IDLE_CONNECTION_LIMIT);
                    } catch (RuntimeException ex) {
                        LOG.warning("idle connection sweep failed: " + ex.getClass().getName());
                    }
                }, 1, 1, TimeUnit.MINUTES);
            }
        }
    }

    /** Package-visible for lifecycle race regression tests. */
    boolean backgroundRunning() {
        synchronized (lifecycle) {
            return scheduler != null && !scheduler.isShutdown();
        }
    }

    /** Starts retention, sync, and report loops. Safe to call once after HTTP is listening. */
    public void startServing() {
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
        prepare();
        synchronized (lifecycle) {
            if (closed.get()) {
                throw new IllegalStateException("runtime is closed");
            }
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
        AuditLog.purgeBefore(database.dataSource(), clock.instant().minus(CONFIG_AUDIT_RETENTION));
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
                    apiKeyId != null ? CallChannel.MCP : CallChannel.CONSOLE), result.stepLogs());
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
            recorder.close();
            fileStage.close();
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
                clock.instant(),
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
                syncSettings, paths, registry.scoped(Origin.OFFICIAL), vault, database.dataSource(), clock, disabledConches);
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
