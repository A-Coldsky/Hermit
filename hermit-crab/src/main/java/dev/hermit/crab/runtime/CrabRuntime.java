package dev.hermit.crab.runtime;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.engine.EngineSettings;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.AccountStore;
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
import dev.hermit.crab.store.ShellConfigStore;

/**
 * Composition root for one Crab process. Slice 2d attaches {@link ShellSync}, {@link ReportQueue},
 * and {@link RollbackGuard}; until then sync reports {@code NOT_CONFIGURED} and the per-call hooks
 * stay unset. Listeners run after every engine execution.
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
    private final ShellConfigStore shellConfig;
    private final ApiKeyService apiKeys;
    private final ShellRegistry registry = new ShellRegistry();
    private final EngineSettings settings;
    private final SessionManager sessions;
    private final RuleEngine engine;
    private final CallLogStore callLogs;
    private final IdempotencyStore idempotency;
    private final CapabilitySwitchStore switches;
    private final DebugStateStore debug;
    private final Set<String> configDisabled;
    private final Set<String> switchDisabled;
    private final Invoker invoker;
    private final ShellAdmin admin;
    private final InstalledShellReader installedShells;
    private final String instanceId;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ShellSync shellSync;
    private ScheduledExecutorService scheduler;

    public interface ExecutionListener {
        void onResult(Invocation invocation, ExecutionResult result);
    }

    private CrabRuntime(CrabPaths paths, CrabConfig config, Function<String, String> environment, Clock clock) {
        this.paths = paths;
        this.config = config;
        this.clock = clock;
        this.database = new CrabDatabase(paths);
        byte[] masterKey = MasterKeyProvider.load(paths, Optional.empty(), environment);
        try {
            this.cipher = new CredentialCipher(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
        this.accounts = new AccountStore(database.dataSource());
        this.vault = new CredentialVault(database.dataSource(), cipher);
        this.shellConfig = new ShellConfigStore(database.dataSource());
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
        this.invoker = new Invoker(registry, accounts, vault, shellConfig, engine, callLogs, idempotency, this);
        this.admin = new ShellAdmin(database.dataSource(), accounts, vault, shellConfig, apiKeys, sessions, engine);
        this.installedShells = new InstalledShellReader(paths);
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

    public CrabPaths paths() {
        return paths;
    }

    public CrabConfig config() {
        return config;
    }

    public String instanceId() {
        return instanceId;
    }

    public ShellRegistry registry() {
        return registry;
    }

    public Invoker invoker() {
        return invoker;
    }

    public ShellAdmin admin() {
        return admin;
    }

    public ApiKeyService apiKeys() {
        return apiKeys;
    }

    public CallLogStore callLogs() {
        return callLogs;
    }

    public DebugStateStore debug() {
        return debug;
    }

    public EngineSettings settings() {
        return settings;
    }

    public void loadInstalledShells() {
        for (ShellDoc doc : installedShells.loadAll()) {
            registry.put(doc);
        }
    }

    public InstalledShellReader installedShells() {
        return installedShells;
    }

    public Optional<Action> enabledAction(String shell, String action) {
        ShellDoc doc = registry.find(shell).orElse(null);
        if (doc == null) {
            return Optional.empty();
        }
        Action found = doc.actions().get(action);
        if (found == null || disabled(shell, action)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public boolean disabled(String shell, String action) {
        String key = shell + "." + action;
        return configDisabled.contains(key) || switchDisabled.contains(key);
    }

    public void setCapabilityDisabled(String shell, String action, boolean disabled, String actor) {
        ShellDoc doc = registry.find(shell).orElse(null);
        if (doc == null || !doc.actions().containsKey(action)) {
            throw new IllegalArgumentException("capability not found");
        }
        switches.setDisabled(Origin.OFFICIAL, shell, action, disabled, actor);
        String key = shell + "." + action;
        if (disabled) {
            switchDisabled.add(key);
        } else {
            switchDisabled.remove(key);
        }
    }

    public void addListener(ExecutionListener listener) {
        invoker.addListener(listener);
    }

    public void attach(ShellSync shellSync) {
        this.shellSync = shellSync;
    }

    public void attach(ReportQueue reportQueue) {
        invoker.setReportQueue(reportQueue);
    }

    public void attach(RollbackGuard rollbackGuard) {
        invoker.setRollbackGuard(rollbackGuard);
    }

    public boolean syncNow(String actor) {
        ShellSync sync = shellSync;
        if (sync == null) {
            return false;
        }
        sync.syncNow();
        AuditLog.record(database.dataSource(), actor, AuditKind.SYNC_REQUESTED, "sync", "{}");
        return true;
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
        ShellSync sync = shellSync;
        if (sync != null) {
            sync.start();
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
        ShellSync sync = shellSync;
        if (sync != null) {
            try {
                sync.close();
            } catch (Exception ex) {
                LOG.warning("shell sync close failed: " + ex.getClass().getName());
            }
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
}
