package dev.hermit.crab.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.EngineSettings;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.report.ReportSender;
import dev.hermit.crab.report.ReportSettings;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.secure.MasterKeyException;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyService;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CapabilitySwitchStore;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.DebugStateStore;
import dev.hermit.crab.store.EncryptedSessionPersistence;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;
import dev.hermit.crab.sync.CredentialDeclarationChange;
import dev.hermit.crab.sync.DisabledConches;
import dev.hermit.crab.sync.InstalledConches;
import dev.hermit.crab.sync.LocalConches;
import dev.hermit.crab.sync.LoadReport;
import dev.hermit.crab.sync.RollbackGuard;
import dev.hermit.crab.sync.ConchSync;
import dev.hermit.crab.sync.SyncReport;
import dev.hermit.crab.sync.SyncSettings;

/**
 * Composition root for one Crab process. {@link #loadInstalledConches()} verifies packages when a
 * Reef public key is configured and binds sync, rollback, and report hooks when {@code reef.url}
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
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilitySwitchStore switches;
    private final DebugStateStore debug;
    private final Set<String> configDisabled;
    private final Set<String> switchDisabled;
    private final List<Runnable> catalogListeners = new CopyOnWriteArrayList<>();
    private final DisabledConches disabledConches;
    private final Invoker invoker;
    private final ConchAdmin admin;
    private final LocalConches localConches;
    private final String instanceId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean prepared = new AtomicBoolean();
    private PublicKey reefKey;
    private ConchSync conchSync;
    private ReportQueue reportQueue;
    private ReportSender reportSender;
    /** Last completed sync, from startup, the timer, or a manual request. Sync threads write; HTTP threads read. */
    private volatile SyncReport lastSync;
    private ScheduledExecutorService scheduler;

    public interface ExecutionListener {
        void onResult(Invocation invocation, ExecutionResult result);
    }

    private CrabRuntime(CrabPaths paths, CrabConfig config, Function<String, String> environment, Clock clock) {
        this.paths = paths;
        this.config = config;
        this.clock = clock;
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
        this.sessions = new SessionManager(new EncryptedSessionPersistence(database.dataSource(), cipher), settings, clock);
        this.engine = new RuleEngine(registry, sessions, settings);
        this.callLogs = new CallLogStore(database.dataSource(), clock);
        this.idempotency = new IdempotencyStore(database.dataSource(), cipher, clock);
        this.switches = new CapabilitySwitchStore(database.dataSource());
        this.debug = new DebugStateStore(database.dataSource(), clock);
        this.configDisabled = Set.copyOf(config.disabledCapabilities());
        this.switchDisabled = ConcurrentHashMap.newKeySet();
        this.switchDisabled.addAll(switches.disabledKeys(Origin.OFFICIAL));
        this.switchDisabled.addAll(switches.disabledKeys(Origin.LOCAL));
        this.disabledConches = new DisabledConches(database.dataSource());
        this.disabledConches.load();
        this.invoker = new Invoker(
                registry, accounts, vault, conchConfig, engine, callLogs, idempotency, this, disabledConches);
        this.admin = new ConchAdmin(
                database.dataSource(), accounts, vault, conchConfig, apiKeys, sessions, engine, registry, disabledConches);
        this.localConches = new LocalConches(paths, registry, database.dataSource());
        this.instanceId = InstanceIds.loadOrCreate(paths);
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config) {
        return open(paths, config, System::getenv, Clock.systemUTC());
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config, Function<String, String> environment) {
        return open(paths, config, environment, Clock.systemUTC());
    }

    public static CrabRuntime open(CrabPaths paths, CrabConfig config, Function<String, String> environment, Clock clock) {
        return new CrabRuntime(paths, config, environment, clock);
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
        if (config.reefUrl() == null) {
            return;
        }
        if (reefKey == null) {
            throw new IllegalStateException("reef.url is set but reef.public_key is missing");
        }
        URI reefUrl = reefUri(config.reefUrl());
        UUID instance = UUID.fromString(instanceId);
        Optional<String> token = Optional.ofNullable(config.reefToken()).filter(value -> !value.isBlank());
        SyncSettings syncSettings = new SyncSettings(
                reefUrl, token, reefKey, Duration.ofSeconds(config.syncIntervalSeconds()), instance);
        ConchSync sync = new ConchSync(
                syncSettings, paths, registry, vault, database.dataSource(), clock, disabledConches);
        sync.onReport(this::rememberCompletedSync);
        this.conchSync = sync;
        ReportSettings reportSettings = new ReportSettings(
                config.reportEnabled(), reefUrl, token, instance, CrabVersion.CURRENT.toString());
        this.reportQueue = new ReportQueue(reportSettings, database.dataSource(), clock);
        RollbackGuard rollbackGuard = new RollbackGuard(
                paths, reefKey, registry, database.dataSource(), reportQueue, clock);
        invoker.bindHooks(reportQueue, rollbackGuard);
        if (config.reportEnabled()) {
            this.reportSender = new ReportSender(reportSettings, reportQueue, clock);
        }
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
        LoadReport official = InstalledConches.loadAll(paths, reefKey, registry, disabledConches.names());
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

    public ReportSender reportSender() {
        return reportSender;
    }

    public boolean reportsConfigured() {
        return config.reportEnabled() && reportSender != null;
    }

    public Optional<SyncReport> lastSync() {
        return Optional.ofNullable(lastSync);
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
        switches.setDisabled(registry.origin(conch).orElse(Origin.OFFICIAL), conch, action, disabled, actor);
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
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "hermit-crab-retention");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::safePurge, 1, 1, TimeUnit.HOURS);
        }
    }

    public void purgeExpired() {
        callLogs.purgeBefore(clock.instant().minus(CALL_LOG_RETENTION));
        idempotency.purgeBefore(clock.instant().minus(IdempotencyStore.TTL));
        debug.expire();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        sessions.close();
        database.close();
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
            LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
            detail.put("addedRequired", new ArrayList<>(new TreeSet<>(change.addedRequired())));
            detail.put("removed", new ArrayList<>(new TreeSet<>(change.removed())));
            AuditLog.record(
                    database.dataSource(),
                    Actors.SYNC,
                    AuditKind.CREDENTIAL_DECLARATION,
                    change.conch(),
                    ApiJson.write(detail));
        }
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
