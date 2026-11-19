package dev.hermit.crab.sync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.CredentialField;
import dev.hermit.crab.upstream.McpOAuthToken;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.pkg.PackageReason;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchManifest;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.version.SemVer;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.store.AccountNotFoundException;
import dev.hermit.crab.tls.OutboundTls;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

public final class ConchSync {

    private static final Logger LOG = Logger.getLogger(ConchSync.class.getName());
    static final Duration DISPLAY_WAIT = Duration.ofSeconds(2);

    private final AtomicReference<SyncSettings> settings;
    private final CrabPaths paths;
    private final ConchRegistry registry;
    private final CredentialVault vault;
    private final DataSource dataSource;
    private final SyncStore store;
    private final Subscriptions subscriptions;
    private final DisabledConches disabledConches;
    private final ReefClient reef;
    private final Clock clock;
    private final HttpClient client;
    private final SemVer crabVersion = CrabVersion.CURRENT;
    private final AtomicBoolean started = new AtomicBoolean();
    /**
     * 同步、订阅、更新、回滚的互斥锁。同步拿着它下载清单和包，最长要等 Reef 超时；
     * 控制台的只读查询用 {@link #forDisplay} 限时等待，不再一直卡在同步后面。
     */
    private final ReentrantLock syncLock = new ReentrantLock();
    private volatile Consumer<SyncReport> reportListener;
    private int credentialDeleteFailures;

    public ConchSync(
            SyncSettings settings,
            CrabPaths paths,
            ConchRegistry registry,
            CredentialVault vault,
            DataSource dataSource
    ) {
        this(settings, paths, registry, vault, dataSource, Clock.systemUTC());
    }

    public ConchSync(
            SyncSettings settings,
            CrabPaths paths,
            ConchRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock
    ) {
        this(settings, paths, registry, vault, dataSource, clock, new DisabledConches(dataSource), true);
    }

    public ConchSync(
            SyncSettings settings,
            CrabPaths paths,
            ConchRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock,
            DisabledConches disabledConches
    ) {
        this(settings, paths, registry, vault, dataSource, clock, disabledConches, false);
    }

    private ConchSync(
            SyncSettings settings,
            CrabPaths paths,
            ConchRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock,
            DisabledConches disabledConches,
            boolean loadDisabled
    ) {
        this.settings = new AtomicReference<>(settings);
        this.paths = paths;
        this.registry = registry;
        this.vault = vault;
        this.dataSource = dataSource;
        this.store = new SyncStore(dataSource);
        this.subscriptions = new Subscriptions(dataSource);
        this.disabledConches = disabledConches == null ? new DisabledConches(dataSource) : disabledConches;
        if (loadDisabled || disabledConches == null) {
            this.disabledConches.load();
        }
        this.clock = clock;
        this.client = OutboundTls.current().httpClientBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.reef = new ReefClient(this.settings, client);
    }

    public DisabledConches disabledConches() {
        return disabledConches;
    }

    public SyncSettings currentSettings() {
        return settings.get();
    }

    /**
     * Replaces the Reef URL, token, and public key. Waits for an in-progress sync to finish
     * so a download is not verified with a different key than the one that started the sync.
     */
    public void retarget(SyncSettings next) {
        syncLock.lock();
        try {
            settings.set(Objects.requireNonNull(next, "settings"));
        } finally {
            syncLock.unlock();
        }
    }

    private SyncSettings settings() {
        return settings.get();
    }

    /**
     * Receives each report before {@link #syncOnce()} returns. Not called when sync throws or
     * produces no report. Runs on the sync thread while the sync lock is held; must not call
     * {@link #syncOnce()}.
     */
    public void onReport(Consumer<SyncReport> listener) {
        this.reportListener = listener;
    }

    public SyncReport syncOnce() {
        syncLock.lock();
        try {
            return syncOnceLocked();
        } finally {
            syncLock.unlock();
        }
    }

    private SyncReport syncOnceLocked() {
        try {
            retryPendingCredentialDeletes();
            Optional<CrabRelease> latest = readLatest();
            ReefClient.ManifestFetch manifest = reef.manifest(store.etag().orElse(null));
            if (manifest.notModified()) {
                if (snapshotMatches() && store.catalog().isPresent()) {
                    if (manifest.etag() != null) {
                        store.saveEtag(manifest.etag());
                    }
                    return deliver(unchanged(latest));
                }
                // The stored tag no longer describes this disk, or the catalog cache was never
                // saved. Drop the tag and read the full manifest once; a second 304 must not loop.
                store.clearEtagAndSnapshot();
                manifest = reef.manifest(null);
                if (manifest.notModified()) {
                    return deliver(unchanged(latest));
                }
            }
            return deliver(install(manifest, latest));
        } catch (SyncException ex) {
            // Record before leaving the sync lock so a later success cannot be overwritten
            // by this attempt, and this attempt cannot be overwritten by an older success.
            ex.clientError().filter(SyncErrors::replacesLastSuccess).ifPresent(error -> deliver(SyncReport.failed(error)));
            throw ex;
        }
    }

    public ConchCatalog catalog() {
        return forDisplay(this::readCatalog);
    }

    /**
     * Subscribes, then syncs under this lock so a 304 cannot skip the install.
     * The manifest is fetched again before a new row is written, even when a catalog
     * cache already exists. A name the fresh manifest does not list is not inserted.
     * An existing subscription is left in place and still synced, matching repeat subscribe.
     * {@code onEachReport} sees the install sync. The caller writes one {@code SYNC_REQUESTED}
     * audit after this returns.
     */
    public ConchOutcome subscribe(String conch, String actor, Consumer<SyncReport> onEachReport) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        syncLock.lock();
        try {
            if (!ConchValidator.isConchName(conch)) {
                throw new SubscriptionException(400, ErrorCode.INVALID_INPUT, SubscriptionException.NAME_INVALID);
            }
            if (registry.origin(conch) == Origin.LOCAL) {
                throw new SubscriptionException(409, ErrorCode.INVALID_INPUT, SubscriptionException.LOCAL_CONFLICT);
            }
            boolean already = subscriptions.contains(conch);
            refreshCatalog();
            boolean listed = cached(conch).isPresent();
            if (!listed && !already) {
                throw new SubscriptionException(404, ErrorCode.NOT_FOUND, SubscriptionException.CANNOT_SUBSCRIBE);
            }
            boolean inserted = listed && subscriptions.record(conch, actor, clock.instant());
            store.clearEtagAndSnapshot();
            SyncReport report;
            try {
                report = syncOnceLocked();
            } catch (RuntimeException ex) {
                if (inserted) {
                    auditSubscribed(conch, actor);
                }
                throw ex;
            }
            if (onEachReport != null && report != null) {
                onEachReport.accept(report);
            }
            ConchOutcome outcome = report == null ? null : outcomeFor(report, conch);
            if (inserted && outcome == null) {
                subscriptions.delete(conch);
                return null;
            }
            if (inserted) {
                auditSubscribed(conch, actor);
            }
            return outcome;
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Replaces the catalog cache from a manifest fetch that does not send the stored ETag.
     * A package Reef stopped listing between syncs must not stay subscribable because the
     * cache still has its name. Connection failures are recorded the same way as {@link #syncOnceLocked()}.
     */
    private void refreshCatalog() {
        ReefClient.ManifestFetch manifest;
        try {
            manifest = reef.manifest(null);
        } catch (SyncException ex) {
            ex.clientError().filter(SyncErrors::replacesLastSuccess).ifPresent(error -> deliver(SyncReport.failed(error)));
            throw ex;
        }
        if (manifest.notModified()) {
            return;
        }
        store.saveCatalog(manifest.entries(), clock.instant());
    }

    private void auditSubscribed(String conch, String actor) {
        AuditLog.record(dataSource, actor, AuditKind.CONCH_SUBSCRIBED, "OFFICIAL/" + conch, conch);
    }

    public void unsubscribe(String conch, String actor) {
        SubscriptionCommands.unsubscribe(
                syncLock, paths, registry, disabledConches, subscriptions, dataSource, conch, actor);
    }

    /**
     * 只给控制台显示用。同步正在下载时最多等 {@link #DISPLAY_WAIT}，等不到就不加锁直接读：
     * 文件按原子替换写入，库读在各自的事务里，读到的是切换前或切换后的样子，最多短暂不一致。
     * 切换、授权和调用都不走这里。
     */
    private <T> T forDisplay(Supplier<T> read) {
        boolean locked = false;
        try {
            locked = syncLock.tryLock(DISPLAY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            return read.get();
        } finally {
            if (locked) {
                syncLock.unlock();
            }
        }
    }

    /**
     * Installs the last synced catalog version when it is strictly higher than the loaded one.
     * Holds this lock so it does not race {@link #syncOnce()}. Does not refetch the manifest.
     * When the package was installed before but is not loaded, a verified zip is reloaded and
     * {@code current} stays on that version. The manifest version is downloaded only when that
     * zip does not verify. When the default account lacks a newly required credential, the
     * verified zip of a different version is stored under the version directory and the current
     * pointer stays put.
     */
    public UpdateResult update(String conch, String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        syncLock.lock();
        try {
            if (!ConchValidator.isConchName(conch)) {
                throw new UpdateException(400, ErrorCode.INVALID_INPUT, SubscriptionException.NAME_INVALID);
            }
            if (isLocal(conch)) {
                throw new UpdateException(409, ErrorCode.INVALID_INPUT, UpdateException.LOCAL_CONFLICT);
            }
            if (!subscriptions.contains(conch)) {
                throw new UpdateException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_SUBSCRIBED);
            }
            if (disabledConches.contains(conch)) {
                // Tombstone means Reef stopped distributing. Do not download, whether or not
                // the local files are still loaded.
                throw new UpdateException(409, ErrorCode.CONCH_DISABLED, disabledUpdateMessage(conch));
            }
            ReefClient.CatalogEntry entry = cached(conch).orElseThrow(() ->
                    new UpdateException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_IN_CATALOG));
            if (!officiallyLoaded(conch) && previouslyInstalled(conch)) {
                return updateUnloaded(conch, actor, entry);
            }
            String installedText = installedVersionText(conch);
            if (installedText == null) {
                throw new UpdateException(404, ErrorCode.NOT_FOUND, UpdateException.NOT_INSTALLED);
            }
            Optional<SemVer> installed = version(installedText);
            Optional<SemVer> incoming = version(entry.version());
            if (installed.isEmpty() || incoming.isEmpty() || !ConchFiles.safeSegment(entry.version())) {
                throw updateRejected(SyncReasons.VERSION_INVALID);
            }
            if (incoming.get().compareTo(installed.get()) <= 0) {
                throw new UpdateException(409, ErrorCode.INVALID_INPUT, UpdateException.NOT_HIGHER);
            }
            return downloadActivateAndAudit(entry, actor, installedText, installedText, entry.version(), false);
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Installs the single previous version kept by the last successful switch.
     * Sync never calls this. A target the validator rejects is left on disk and the working version stays.
     */
    public UpdateResult rollback(String conch, String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        syncLock.lock();
        try {
            if (!ConchValidator.isConchName(conch)) {
                throw new UpdateException(400, ErrorCode.INVALID_INPUT, SubscriptionException.NAME_INVALID);
            }
            if (isLocal(conch)) {
                throw new UpdateException(409, ErrorCode.CONFLICT, "本地规则包没有可回退的上一版。");
            }
            if (!subscriptions.contains(conch)) {
                throw new UpdateException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_SUBSCRIBED);
            }
            String target = store.previous(conch).orElse(null);
            String current = ConchFiles.readCurrent(paths, conch).orElse(null);
            if (target == null || target.equals(current) || !ConchFiles.safeSegment(target)) {
                throw new UpdateException(409, ErrorCode.CONFLICT, "没有可回退的上一版。");
            }
            VerifiedConch verified;
            try {
                Path zip = ConchFiles.versionDir(paths, conch, target).resolve("package.zip");
                verified = ConchPackage.readVerified(ConchFiles.readZip(paths, zip), settings().reefKey());
                if (!conch.equals(verified.manifest().conch()) || !target.equals(verified.manifest().version())) {
                    throw new UpdateException(409, ErrorCode.CONFLICT, "上一版无法读取。当前版本没有改动。");
                }
            } catch (UpdateException ex) {
                throw ex;
            } catch (RuntimeException | IOException ex) {
                throw new UpdateException(409, ErrorCode.CONFLICT, "上一版无法读取。当前版本没有改动。");
            }
            List<ValidationIssue> issues = registry.inspect(verified.conchDoc());
            if (!issues.isEmpty()) {
                String yaml = new String(verified.conchYaml(), StandardCharsets.UTF_8);
                throw new UpdateException(409, ErrorCode.CONFLICT,
                        RuleDisabled.rollbackBlocked(target, ImportIssueLines.locate(yaml, issues)));
            }
            ConchDoc previous = previousDoc(conch);
            Origin previousOrigin = registry.origin(conch);
            Optional<CredentialDeclarationChange> change = CredentialChanges.between(previous, verified.conchDoc());
            try {
                registry.put(verified.conchDoc(), Origin.OFFICIAL);
            } catch (ConchRejectedException ex) {
                throw new UpdateException(409, ErrorCode.CONFLICT,
                        RuleDisabled.rollbackBlocked(target, ImportIssueLines.locate(
                                new String(verified.conchYaml(), StandardCharsets.UTF_8), ex.issues())));
            }
            try {
                ConchFiles.writeCurrent(paths, conch, target);
            } catch (RuntimeException ex) {
                registryRestore(conch, previous, previousOrigin == null ? Origin.OFFICIAL : previousOrigin);
                throw new UpdateException(409, ErrorCode.CONFLICT, SyncReasons.CURRENT_WRITE_FAILED);
            }
            if (current != null && !current.equals(target)) {
                store.setPrevious(conch, current);
            } else if (current == null) {
                store.clearPrevious(conch);
            }
            store.recordInstalled(conch, target, clock.instant());
            try {
                ConchFiles.prune(paths, conch, VersionRetention.keep(
                        target,
                        store.latestSuccessExcept(conch, target),
                        store.previous(conch),
                        store.installed(conch)));
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "rollback prune failed conch={0}", conch);
            }
            store.clearRepairNotice(conch);
            return new UpdateResult(
                    new ConchOutcome(conch, target, OutcomeKind.INSTALLED, null),
                    change,
                    current,
                    target);
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * The subscription installed this conch before, and it is not loaded. Reload the newest
     * installed zip when it still verifies. Download the manifest version only when it does not.
     * A Reef disable is rejected by {@link #update} before this runs, so a tombstone is not reinstalled.
     */
    private UpdateResult updateUnloaded(String conch, String actor, ReefClient.CatalogEntry entry) {
        String recorded = newestInstalled(conch);
        if (recorded != null && localVerifyFailure(conch, recorded).isEmpty()) {
            Reload reload = reloadVerified(conch, recorded);
            if (reload.kind() == ReloadKind.LOADED) {
                return new UpdateResult(
                        new ConchOutcome(conch, recorded, OutcomeKind.REPAIRED, SyncReasons.RESTORED),
                        Optional.empty(),
                        null,
                        null);
            }
            if (reload.kind() == ReloadKind.WRITE_FAILED) {
                throw updateRejected(SyncReasons.CURRENT_WRITE_FAILED);
            }
            if (higherInstallable(entry, recorded)) {
                String reportedFrom = recorded.equals(entry.version()) ? null : recorded;
                String reportedTo = reportedFrom == null ? null : entry.version();
                return downloadActivateAndAudit(entry, actor, recorded, reportedFrom, reportedTo, true);
            }
            throw updateRejected(reload.explanation() == null ? RuleDisabled.LEAD : reload.explanation());
        }
        if (!version(entry.version()).isPresent() || !ConchFiles.safeSegment(entry.version())) {
            throw updateRejected(SyncReasons.VERSION_INVALID);
        }
        String reportedFrom = recorded != null && !recorded.equals(entry.version()) ? recorded : null;
        String reportedTo = reportedFrom == null ? null : entry.version();
        return downloadActivateAndAudit(entry, actor, recorded, reportedFrom, reportedTo, true);
    }

    private UpdateResult downloadActivateAndAudit(
            ReefClient.CatalogEntry entry,
            String actor,
            String fromVersion,
            String reportedFrom,
            String reportedTo,
            boolean replaceUnreadable
    ) {
        if (entry.sha256() == null || entry.minCrabVersion() == null) {
            throw updateRejected(SyncReasons.MANIFEST_ENTRY_MISMATCH);
        }
        if (!minCrabAllows(entry.minCrabVersion())) {
            throw updateRejected(version(entry.minCrabVersion()).isEmpty()
                    ? SyncReasons.VERSION_INVALID
                    : SyncReasons.MIN_CRAB_VERSION);
        }
        Fetched fetched = fetchVerified(entry);
        if (fetched.rejected() != null) {
            throw updateRejected(fetched.rejected().reason());
        }
        VerifiedConch verified = fetched.verified();
        if (entry.requiredCredentialsKnown()
                && !requiredNames(verified.conchDoc()).equals(entry.requiredCredentials())) {
            throw updateRejected(SyncReasons.MANIFEST_ENTRY_MISMATCH);
        }
        if (verified.conchDoc().minCrabVersion().compareTo(crabVersion) > 0) {
            throw updateRejected(SyncReasons.MIN_CRAB_VERSION);
        }
        String conch = entry.conch();
        ConchDoc installedDoc = registry.find(conch).orElse(null);
        List<String> newly = installedDoc == null
                ? requiredNames(verified.conchDoc())
                : sorted(ConchDiff.between(installedDoc, verified.conchDoc()).newRequiredCredentials());
        List<String> missing = missingOnDefaultAccount(conch, newly);
        if (!missing.isEmpty()) {
            // Same-version bytes stay untouched so a later update cannot reload a staged zip
            // and skip this credential check. A different version is staged, and current stays.
            if (fromVersion == null || !fromVersion.equals(entry.version())) {
                synchronized (ConchFileLocks.of(conch)) {
                    if (isLocal(conch)) {
                        throw new UpdateException(409, ErrorCode.INVALID_INPUT, UpdateException.LOCAL_CONFLICT);
                    }
                    ConchFiles.install(paths, conch, entry.version(), fetched.zip(), verified.conchYaml());
                }
            }
            throw new UpdateException(
                    409,
                    ErrorCode.NOT_CONFIGURED,
                    UpdateException.MISSING_PREFIX + String.join(", ", missing),
                    missing);
        }
        InstallResult activated = activate(entry, verified, fetched.zip(), fromVersion, replaceUnreadable);
        if (activated.outcome().kind() != OutcomeKind.INSTALLED) {
            String reason = activated.outcome().reason();
            if (reason != null && reason.startsWith("新版本 v") && reason.contains("装不上")) {
                throw new UpdateException(409, ErrorCode.CONFLICT, reason);
            }
            throw updateRejected(reason);
        }
        String auditedFrom = fromVersion == null ? "" : fromVersion;
        AuditLog.record(
                dataSource,
                actor,
                AuditKind.CONCH_UPDATED,
                "OFFICIAL/" + conch,
                "{\"from\":\"" + auditedFrom + "\",\"to\":\"" + entry.version() + "\"}");
        store.clearRepairNotice(conch);
        return new UpdateResult(activated.outcome(), activated.change(), reportedFrom, reportedTo);
    }

    /** Caller holds the sync lock. A missing registry entry means the local copy is gone. */
    private String disabledUpdateMessage(String conch) {
        if (registry.find(conch).isEmpty()) {
            return DisabledConches.CALL_MESSAGE;
        }
        return DisabledConches.UPDATE_MESSAGE;
    }

    /**
     * Verified doc of a higher catalog version already stored on disk. Takes this lock.
     * Callers that already hold the lock must not call this method.
     */
    public Optional<ConchDoc> stagedDeclaration(String conch) {
        syncLock.lock();
        try {
            return readStagedDeclaration(conch);
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Console view of {@code last}. Tombstones are reapplied so a conch Reef dropped stays
     * disabled. Does not touch the registry, so it does not open a version-switch window
     * beside audit A01.
     */
    public SyncReport visibleStatus(SyncReport last) {
        if (last == null) {
            return null;
        }
        return projectRejected(projectDisabled(projectRepairs(last)));
    }

    /**
     * 上次同步说「有新版本」，但手动更新到这个版本已经因为校验失败被拒：概览不能再催用户去更新它，
     * 改成失败行。Reef 出了更高版本后，下一次同步会给出新的行。
     */
    private SyncReport projectRejected(SyncReport last) {
        ArrayList<ConchOutcome> rows = new ArrayList<>();
        boolean changed = false;
        for (ConchOutcome outcome : last.conches()) {
            // UPDATE_AVAILABLE 行带的是已安装版本；清单上推的版本在目录缓存里。
            String offered = outcome.kind() == OutcomeKind.UPDATE_AVAILABLE && outcome.conch() != null
                    ? cached(outcome.conch()).map(ReefClient.CatalogEntry::version).orElse(null)
                    : null;
            if (offered != null && offered.equals(store.rejectedVersion(outcome.conch()).orElse(null))) {
                rows.add(new ConchOutcome(outcome.conch(), offered, OutcomeKind.REJECTED, SyncReasons.UPDATE_FAILED));
                changed = true;
            } else {
                rows.add(outcome);
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(last.notModified(), List.copyOf(rows), last.removed(),
                last.credentialChanges(), last.latestCrab(), last.error());
    }

    /**
     * A 304 report has no conch rows. Put back a repair row when a subscribed install is not
     * loaded, and drop a repair row once that conch is loaded again. Does not download or audit.
     */
    private SyncReport projectRepairs(SyncReport last) {
        LinkedHashMap<String, ConchOutcome> rows = new LinkedHashMap<>();
        for (ConchOutcome outcome : last.conches()) {
            if (outcome.conch() != null) {
                rows.put(outcome.conch(), outcome);
            }
        }
        boolean changed = false;
        for (String conch : subscriptions.names()) {
            if (conch == null || disabledConches.contains(conch) || isLocal(conch)) {
                continue;
            }
            if (!unloadedPreviousInstall(conch)) {
                ConchOutcome current = rows.get(conch);
                if (current != null
                        && (current.kind() == OutcomeKind.REPAIR_REQUIRED || current.kind() == OutcomeKind.RULE_DISABLED)
                        && officiallyLoaded(conch)) {
                    rows.remove(conch);
                    changed = true;
                }
                continue;
            }
            ConchOutcome current = rows.get(conch);
            String pointer = ConchFiles.readCurrent(paths, conch).orElse(null);
            boolean ruleDisabled = validationDisableMessage(conch, pointer) != null;
            OutcomeKind kind = ruleDisabled ? OutcomeKind.RULE_DISABLED : OutcomeKind.REPAIR_REQUIRED;
            String reason = ruleDisabled ? SyncReasons.RULE_DISABLED : unloadedReason(conch);
            if (current != null && current.kind() == kind && reason.equals(current.reason())) {
                continue;
            }
            String version = ruleDisabled ? semverOrNull(pointer) : repairVersion(conch, reason);
            rows.put(conch, new ConchOutcome(conch, version, kind, reason));
            changed = true;
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(last.notModified(), List.copyOf(rows.values()), last.removed(),
                last.credentialChanges(), last.latestCrab(), last.error());
    }

    /**
     * A later 304 replaces the conch rows. Tombstones are reapplied here so the console still
     * shows the conch as disabled, without touching the registry (audit A01). The name is not
     * also listed in {@code removed}.
     */
    private SyncReport projectDisabled(SyncReport last) {
        List<String> names = new ArrayList<>(disabledConches.names());
        names.sort(String::compareTo);
        if (names.isEmpty()) {
            return last;
        }
        LinkedHashMap<String, ConchOutcome> rows = new LinkedHashMap<>();
        for (ConchOutcome outcome : last.conches()) {
            if (outcome.conch() != null) {
                rows.put(outcome.conch(), outcome);
            }
        }
        LinkedHashSet<String> removed = new LinkedHashSet<>(last.removed());
        boolean changed = false;
        for (String conch : names) {
            if (removed.remove(conch)) {
                changed = true;
            }
            ConchOutcome current = rows.get(conch);
            if (current != null && (current.kind() == OutcomeKind.INSTALLED || current.kind() == OutcomeKind.SKIPPED_SAME)) {
                continue;
            }
            ConchOutcome projected = new ConchOutcome(conch, disabledConches.version(conch), OutcomeKind.DISABLED,
                    SyncReasons.DISABLED);
            if (!projected.equals(current)) {
                rows.put(conch, projected);
                changed = true;
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(last.notModified(), List.copyOf(rows.values()), List.copyOf(removed),
                last.credentialChanges(), last.latestCrab(), last.error());
    }

    private SyncReport install(ReefClient.ManifestFetch manifest, Optional<CrabRelease> latest) {
        store.saveCatalog(manifest.entries(), clock.instant());
        List<ConchOutcome> conches = new ArrayList<>();
        List<CredentialDeclarationChange> changes = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (ReefClient.CatalogEntry entry : manifest.entries()) {
            try {
                if (!ConchValidator.isConchName(entry.conch())) {
                    conches.add(rejected(entry, SyncReasons.CONCH_NAME));
                    continue;
                }
                present.add(entry.conch());
                if (isLocal(entry.conch())) {
                    conches.add(outcome(entry, OutcomeKind.SKIPPED_LOCAL, SyncReasons.LOCAL_KEPT));
                    continue;
                }
                if (subscriptions.contains(entry.conch())) {
                    // Reef listed it again. Drop the tombstone before any download so a later
                    // failure can retry as a normal update.
                    releaseReefDisable(entry.conch());
                }
                if (!version(entry.version()).isPresent()) {
                    conches.add(rejected(entry, SyncReasons.VERSION_INVALID));
                    continue;
                }
                if (!subscriptions.contains(entry.conch())) {
                    conches.add(new ConchOutcome(entry.conch(), entry.version(), OutcomeKind.NOT_SUBSCRIBED, null));
                    continue;
                }
                InstallResult result = installEntry(entry);
                conches.add(result.outcome());
                result.change().ifPresent(changes::add);
            } catch (RuntimeException ex) {
                conches.add(new ConchOutcome(entry.conch(), entry.version(), OutcomeKind.REJECTED, safeReason(ex)));
            }
        }
        List<String> removed = new ArrayList<>();
        // Withdraw only a subscribed official conch Reef dropped. Leave unsubscribed names alone,
        // and leave Origin.LOCAL alone. Both checks stay.
        Map<String, ConchDoc> loaded = registry.snapshot();
        for (String conch : loaded.keySet()) {
            if (present.contains(conch)) {
                continue;
            }
            if (!subscriptions.contains(conch) || registry.origin(conch) == Origin.LOCAL) {
                continue;
            }
            deactivate(conch, conches);
        }
        if (conches.stream().anyMatch(SyncFailures::retryable)) {
            store.clearEtagAndSnapshot();
        } else if (manifest.etag() != null) {
            store.saveEtagAndSnapshot(manifest.etag(), expectedVersions(conches));
        }
        return new SyncReport(false, conches, removed, changes, latest);
    }

    private boolean snapshotMatches() {
        Optional<Map<String, String>> snapshot = store.manifestSnapshot();
        if (snapshot.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : snapshot.get().entrySet()) {
            Optional<String> current = ConchFiles.readCurrent(paths, entry.getKey());
            if (current.isEmpty() || !current.get().equals(entry.getValue())) {
                return false;
            }
            if (unloadedPreviousInstall(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> expectedVersions(List<ConchOutcome> conches) {
        Map<String, String> versions = new TreeMap<>();
        for (ConchOutcome outcome : conches) {
            if (outcome.kind() != OutcomeKind.INSTALLED
                    && outcome.kind() != OutcomeKind.SKIPPED_SAME
                    && outcome.kind() != OutcomeKind.UPDATE_AVAILABLE
                    && outcome.kind() != OutcomeKind.SKIPPED_OLDER
                    && outcome.kind() != OutcomeKind.DISABLED) {
                continue;
            }
            if (outcome.conch() == null || outcome.version() == null) {
                continue;
            }
            versions.put(outcome.conch(), outcome.version());
        }
        return versions;
    }

    private ConchCatalog readCatalog() {
        Optional<List<ReefClient.CatalogEntry>> cached = store.catalog();
        List<ConchCatalog.Entry> rows = new ArrayList<>();
        LinkedHashSet<String> listed = new LinkedHashSet<>();
        if (cached.isPresent()) {
            for (ReefClient.CatalogEntry entry : cached.get()) {
                rows.add(catalogRow(entry));
                if (entry.conch() != null) {
                    listed.add(entry.conch());
                }
            }
        }
        appendAbsentDisabled(rows, listed);
        rows.sort(Comparator.comparing(ConchCatalog.Entry::conch, Comparator.nullsLast(String::compareTo)));
        return new ConchCatalog(true, store.catalogSyncedAt().orElse(null), rows);
    }

    /**
     * A disabled conch has left the Reef manifest, so the cache no longer has a row.
     * Keep one with {@code disabled=true} so the catalog flag matches the tombstone.
     * {@code version} is null: Reef is not publishing a version. {@code installedVersion}
     * is the copy Crab kept.
     */
    private void appendAbsentDisabled(List<ConchCatalog.Entry> rows, Set<String> listed) {
        List<String> names = new ArrayList<>(disabledConches.names());
        names.sort(String::compareTo);
        for (String name : names) {
            if (name == null || listed.contains(name) || isLocal(name)) {
                continue;
            }
            rows.add(absentDisabledRow(name));
        }
    }

    private ConchCatalog.Entry absentDisabledRow(String name) {
        ConchDoc doc = registry.find(name).orElse(null);
        String pointer = ConchFiles.safeSegment(name) ? ConchFiles.readCurrent(paths, name).orElse(null) : null;
        String installedVersion = doc != null ? doc.version().toString() : pointer;
        String title = doc != null && doc.title() != null && !doc.title().isBlank() ? doc.title() : name;
        int count = doc == null || doc.actions() == null ? 0 : doc.actions().size();
        return new ConchCatalog.Entry(
                name,
                null,
                title,
                doc == null ? null : doc.description(),
                count,
                subscriptions.contains(name),
                doc != null || pointer != null,
                installedVersion,
                true,
                false,
                false,
                null,
                List.of(),
                false,
                null,
                null,
                false);
    }

    private ConchCatalog.Entry catalogRow(ReefClient.CatalogEntry entry) {
        String name = entry.conch();
        ConchDoc doc = name == null ? null : registry.find(name).orElse(null);
        String pointer = name == null ? null : ConchFiles.readCurrent(paths, name).orElse(null);
        String title = entry.title();
        if (title == null || title.isBlank()) {
            title = name == null ? "" : name;
        }
        String installedVersion = doc != null ? doc.version().toString() : pointer;
        boolean local = name != null && registry.origin(name) == Origin.LOCAL;
        boolean disabled = name != null && disabledConches.contains(name);
        boolean subscribed = name != null && subscriptions.contains(name);
        boolean untrusted = doc == null && badSignatureUnloaded(name);
        boolean installed = (doc != null || pointer != null) && !untrusted;
        List<String> newly = local || doc == null ? List.of() : newlyRequired(doc, entry.requiredCredentials());
        boolean updateAvailable = !local && !disabled && subscribed && installed
                && recommendUpdate(entry, installedVersion);
        String latestVersion = local ? null : entry.version();
        String ruleMessage = null;
        if (!untrusted && !local && !disabled && subscribed && doc == null && pointer != null
                && previouslyInstalled(name)) {
            ruleMessage = validationDisableMessage(name, pointer);
        }
        boolean ruleDisabled = ruleMessage != null;
        return new ConchCatalog.Entry(
                name,
                entry.version(),
                title,
                entry.description(),
                entry.capabilityCount(),
                subscribed,
                installed,
                installedVersion,
                disabled,
                local,
                updateAvailable,
                latestVersion,
                local ? List.of() : newly,
                untrusted,
                untrusted ? PackageReason.BAD_SIGNATURE.name() : null,
                untrusted ? SignatureDamage.MESSAGE : ruleMessage,
                ruleDisabled);
    }

    /**
     * Subscribed, previously installed, and not loaded because {@code signature.sig} does not verify.
     * A Reef disable tombstone is excluded: manual update refuses to download it.
     * Caller holds this lock, or calls {@link #untrustedSignature(String)}.
     */
    private boolean badSignatureUnloaded(String conch) {
        if (conch == null || !ConchValidator.isConchName(conch)) {
            return false;
        }
        if (isLocal(conch) || disabledConches.contains(conch) || officiallyLoaded(conch)) {
            return false;
        }
        if (!subscriptions.contains(conch) || !previouslyInstalled(conch)) {
            return false;
        }
        return PackageReason.BAD_SIGNATURE.name().equals(unloadedReason(conch));
    }

    /**
     * Display fields for {@link #badSignatureUnloaded(String)}. Empty when the package is loaded,
     * not subscribed, or failed for any other reason.
     */
    public Optional<UntrustedSignature> untrustedSignature(String conch) {
        return forDisplay(() -> {
            if (!badSignatureUnloaded(conch)) {
                return Optional.empty();
            }
            ReefClient.CatalogEntry cached = cached(conch).orElse(null);
            String title = cached == null || cached.title() == null || cached.title().isBlank() ? conch : cached.title();
            String description = cached == null ? null : cached.description();
            String latest = cached == null ? null : cached.version();
            return Optional.of(new UntrustedSignature(conch, title, description, latest));
        });
    }

    /** A subscribed package whose on-disk signature is not trusted and which is not loaded. */
    public record UntrustedSignature(String conch, String title, String description, String latestVersion) {
    }

    /**
     * Subscribed and previously installed, signature verifies, but the current validator will not load it.
     * Files stay. This is not {@link UntrustedSignature} and not a Reef disable.
     */
    public Optional<DisabledRule> disabledRule(String conch) {
        return forDisplay(() -> disabledRuleLocked(conch));
    }

    /** Subscribed packages the current validator will not load. Caller may hold this lock. */
    public List<DisabledRule> disabledRules() {
        return forDisplay(() -> {
            ArrayList<DisabledRule> found = new ArrayList<>();
            for (String name : subscriptions.names()) {
                disabledRuleLocked(name).ifPresent(found::add);
            }
            return List.copyOf(found);
        });
    }

    /** True when that installed version verifies but the current validator will not load it. */
    public boolean versionRejected(String conch, String version) {
        syncLock.lock();
        try {
            return version != null && validationDisableMessage(conch, version) != null;
        } finally {
            syncLock.unlock();
        }
    }

    private Optional<DisabledRule> disabledRuleLocked(String conch) {
        if (conch == null || registry.find(conch).isPresent() || badSignatureUnloaded(conch)) {
            return Optional.empty();
        }
        if (!subscriptions.contains(conch) || !previouslyInstalled(conch) || isLocal(conch)) {
            return Optional.empty();
        }
        String pointer = ConchFiles.readCurrent(paths, conch).orElse(null);
        String message = validationDisableMessage(conch, pointer);
        if (message == null) {
            return Optional.empty();
        }
        VerifiedConch verified = readInstalled(conch, pointer);
        ConchDoc doc = verified == null ? null : verified.conchDoc();
        String title = doc != null && doc.title() != null && !doc.title().isBlank() ? doc.title() : conch;
        String description = doc == null ? null : doc.description();
        ReefClient.CatalogEntry cached = cached(conch).orElse(null);
        PreviousState previous = previousStateLocked(conch);
        boolean updateAvailable = cached != null && recommendUpdate(cached, pointer);
        return Optional.of(new DisabledRule(
                conch,
                pointer,
                title,
                description,
                message,
                previous.version(),
                cached == null ? null : cached.version(),
                previous.disabled(),
                previous.present(),
                RuleDisabled.fixHint(false, previous.rollbackable(), updateAvailable)));
    }

    /** On-disk package for a subscribed install, including one the validator will not load. */
    public Optional<ConchDoc> readableInstalled(String conch) {
        return forDisplay(() -> {
            if (conch == null || !ConchValidator.isConchName(conch) || isLocal(conch)) {
                return Optional.empty();
            }
            String pointer = ConchFiles.safeSegment(conch) ? ConchFiles.readCurrent(paths, conch).orElse(null) : null;
            VerifiedConch verified = readInstalled(conch, pointer);
            return verified == null ? Optional.<ConchDoc>empty() : Optional.of(verified.conchDoc());
        });
    }

    /**
     * Recorded previous version, whether or not its files are still on disk and whether or not
     * the current validator will load it. A missing directory stays recorded so the console can
     * say the file is gone instead of offering a rollback.
     */
    public PreviousState previousState(String conch) {
        if (conch == null || !ConchValidator.isConchName(conch)) {
            return PreviousState.none();
        }
        return forDisplay(() -> previousStateLocked(conch));
    }

    private PreviousState previousStateLocked(String conch) {
        String previous = store.previous(conch).orElse(null);
        if (previous == null || previous.isBlank() || !ConchFiles.safeSegment(previous)) {
            return PreviousState.none();
        }
        // 旧版本同版本重装时会把当前版记成上一版；读出来等于当前版就当没有上一版。
        if (ConchFiles.safeSegment(conch) && previous.equals(ConchFiles.readCurrent(paths, conch).orElse(null))) {
            return PreviousState.none();
        }
        boolean present = versionZipPresent(conch, previous);
        boolean disabled = present && validationDisableMessage(conch, previous) != null;
        return new PreviousState(previous, disabled, present);
    }

    private boolean versionZipPresent(String conch, String version) {
        if (!ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return false;
        }
        Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
        return Files.isRegularFile(zip) && !Files.isSymbolicLink(zip);
    }

    private boolean recommendUpdate(ReefClient.CatalogEntry entry, String installedVersion) {
        if (entry == null || !higherInstallable(entry, installedVersion)) {
            return false;
        }
        String rejected = store.rejectedVersion(entry.conch()).orElse(null);
        if (rejected != null && rejected.equals(entry.version())) {
            return false;
        }
        return validationDisableMessage(entry.conch(), entry.version()) == null;
    }

    public Optional<String> previousVersion(String conch) {
        return Optional.ofNullable(previousState(conch).version());
    }

    /** Display fields for a package the validator will not load. */
    public record DisabledRule(
            String conch,
            String version,
            String title,
            String description,
            String message,
            String previousVersion,
            String latestVersion,
            boolean previousDisabled,
            boolean previousPresent,
            String fixHint
    ) {
    }

    /**
     * Previous version kept after the last switch.
     *
     * @param disabled the zip is still on disk and the current validator will not load it
     * @param present  {@code package.zip} is still a regular file on this machine
     */
    public record PreviousState(String version, boolean disabled, boolean present) {
        public static PreviousState none() {
            return new PreviousState(null, false, false);
        }

        public boolean rollbackable() {
            return version != null && present && !disabled;
        }
    }

    private Optional<ReefClient.CatalogEntry> cached(String conch) {
        Optional<List<ReefClient.CatalogEntry>> catalog = store.catalog();
        if (catalog.isEmpty()) {
            return Optional.empty();
        }
        for (ReefClient.CatalogEntry entry : catalog.get()) {
            if (conch.equals(entry.conch())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static ConchOutcome outcomeFor(SyncReport report, String conch) {
        for (ConchOutcome outcome : report.conches()) {
            if (conch.equals(outcome.conch())) {
                return outcome;
            }
        }
        return null;
    }

    private static SyncReport unchanged(Optional<CrabRelease> latest) {
        return new SyncReport(true, List.of(), List.of(), List.of(), latest);
    }

    private SyncReport deliver(SyncReport report) {
        if (report == null) {
            return null;
        }
        Consumer<SyncReport> listener = reportListener;
        if (listener != null) {
            listener.accept(report);
        }
        return report;
    }

    public void start(ScheduledExecutorService executor) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(1L, settings().interval().toMillis());
        executor.scheduleWithFixedDelay(this::runScheduled, delay, delay, TimeUnit.MILLISECONDS);
    }

    void failNextCredentialDelete() {
        credentialDeleteFailures++;
    }

    void runScheduled() {
        try {
            syncOnce();
        } catch (Exception ex) {
            String detail = ex instanceof SyncException sync
                    ? sync.clientError().map(SyncError::message).orElse(ex.getMessage())
                    : ex.getMessage();
            LOG.log(Level.WARNING, "conch sync failed: {0}", redact(detail));
        }
    }

    private Optional<CrabRelease> readLatest() {
        try {
            return reef.version();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private InstallResult installEntry(ReefClient.CatalogEntry entry) {
        if (isLocal(entry.conch())) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_LOCAL, SyncReasons.LOCAL_KEPT));
        }
        if (!ConchValidator.isConchName(entry.conch())) {
            return InstallResult.of(rejected(entry, SyncReasons.CONCH_NAME));
        }
        Optional<SemVer> parsed = version(entry.version());
        if (parsed.isEmpty()) {
            return InstallResult.of(rejected(entry, SyncReasons.VERSION_INVALID));
        }
        if (!ConchFiles.safeSegment(entry.version())) {
            return InstallResult.of(rejected(entry, SyncReasons.PATH_UNSAFE));
        }
        if (entry.sha256() == null || entry.minCrabVersion() == null) {
            return InstallResult.of(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        SemVer incoming = parsed.get();
        String installedText = installedVersionText(entry.conch());
        Optional<SemVer> min = version(entry.minCrabVersion());
        if (min.isEmpty()) {
            return InstallResult.of(rejected(entry, SyncReasons.VERSION_INVALID));
        }
        if (officiallyLoaded(entry.conch())) {
            store.clearRepairNotice(entry.conch());
        } else if (previouslyInstalled(entry.conch())) {
            return repairRequired(entry);
        }
        if (min.get().compareTo(crabVersion) > 0) {
            String shown = installedText == null ? entry.version() : installedText;
            return InstallResult.of(new ConchOutcome(
                    entry.conch(), shown, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        if (installedText == null) {
            return downloadAndActivate(entry, null);
        }
        Optional<SemVer> installed = version(installedText);
        if (installed.isEmpty()) {
            return downloadAndActivate(entry, null);
        }
        int compared = incoming.compareTo(installed.get());
        if (compared < 0) {
            return InstallResult.of(new ConchOutcome(
                    entry.conch(), installedText, OutcomeKind.SKIPPED_OLDER, SyncReasons.OLDER_MANIFEST));
        }
        if (compared > 0) {
            return InstallResult.of(new ConchOutcome(
                    entry.conch(), installedText, OutcomeKind.UPDATE_AVAILABLE, SyncReasons.UPDATE_AVAILABLE));
        }
        return alignCurrent(entry);
    }

    /**
     * Subscribed, installed before, and not loaded. Do not download. The console reads this row
     * from the sync report. The audit is written once per reason until the package loads or
     * the user unsubscribes.
     */
    private InstallResult repairRequired(ReefClient.CatalogEntry entry) {
        String conch = entry.conch();
        String pointer = ConchFiles.readCurrent(paths, conch).orElse(null);
        if (validationDisableMessage(conch, pointer) != null) {
            return InstallResult.of(new ConchOutcome(
                    conch, semverOrNull(pointer), OutcomeKind.RULE_DISABLED, SyncReasons.RULE_DISABLED));
        }
        String reason = unloadedReason(conch);
        noteRepair(conch, reason);
        return InstallResult.of(new ConchOutcome(
                conch, repairVersion(conch, reason), OutcomeKind.REPAIR_REQUIRED, reason));
    }

    private InstallResult downloadAndActivate(ReefClient.CatalogEntry entry, String previousCurrent) {
        Fetched fetched = fetchVerified(entry);
        if (fetched.rejected() != null) {
            return InstallResult.of(fetched.rejected());
        }
        if (fetched.verified().conchDoc().minCrabVersion().compareTo(crabVersion) > 0) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        return activate(entry, fetched.verified(), fetched.zip(), previousCurrent, false);
    }

    private InstallResult activate(
            ReefClient.CatalogEntry entry,
            VerifiedConch verified,
            byte[] zip,
            String previousCurrent,
            boolean replaceUnreadable
    ) {
        String conch = entry.conch();
        String version = entry.version();
        synchronized (ConchFileLocks.of(conch)) {
            if (isLocal(conch)) {
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_LOCAL, SyncReasons.LOCAL_KEPT));
            }
            Optional<String> currentNow = ConchFiles.readCurrent(paths, conch);
            if (currentNow.isPresent() && currentNow.get().equals(version)) {
                ConchDoc loaded = registry.find(conch).orElse(null);
                boolean loadedMatches = loaded != null && version.equals(loaded.version().toString());
                if (loadedMatches || (loaded == null && loadInstalled(conch, version))) {
                    releaseReefDisable(conch);
                    return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
                }
                if (loaded == null && !replaceUnreadable) {
                    return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
                }
            }
            ConchDoc previous = previousDoc(conch);
            Origin previousOrigin = registry.origin(conch);
            if (previousOrigin == null) {
                previousOrigin = Origin.OFFICIAL;
            }
            Optional<CredentialDeclarationChange> change = CredentialChanges.between(previous, verified.conchDoc());
            ConchFiles.install(paths, conch, version, zip, verified.conchYaml());
            try {
                registry.put(verified.conchDoc(), Origin.OFFICIAL);
            } catch (ConchRejectedException ex) {
                String yaml = new String(verified.conchYaml(), StandardCharsets.UTF_8);
                List<ValidationIssue> located = ImportIssueLines.locate(yaml, ex.issues());
                ConchFiles.deleteVersion(paths, conch, version);
                store.rememberRejected(conch, version);
                String explained = previous != null
                        ? RuleDisabled.updateBlocked(version, previous.version().toString(), located)
                        : RuleDisabled.explain(located);
                return InstallResult.of(new ConchOutcome(conch, version, OutcomeKind.REJECTED, explained));
            } catch (RuntimeException ex) {
                ConchFiles.deleteVersion(paths, conch, version);
                registryRestore(conch, previous, previousOrigin);
                return InstallResult.of(rejected(entry, SyncReasons.REGISTRY_REJECTED));
            }
            try {
                ConchFiles.writeCurrent(paths, conch, version);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "current pointer write failed conch={0}", conch);
                registryRestore(conch, previous, previousOrigin);
                ConchFiles.deleteVersion(paths, conch, version);
                return InstallResult.of(rejected(entry, SyncReasons.CURRENT_WRITE_FAILED));
            }
            String switchedFrom = currentNow.orElse(previousCurrent);
            if (switchedFrom == null) {
                store.clearPrevious(conch);
            } else if (!switchedFrom.equals(version)) {
                store.setPrevious(conch, switchedFrom);
            }
            // 同版本重装没有换版本：上一版保持原样，不能把当前版记成上一版（否则会出现「退回到自己」）。
            store.recordInstalled(conch, version, clock.instant());
            try {
                ConchFiles.prune(paths, conch, VersionRetention.keep(
                        version,
                        store.latestSuccessExcept(conch, version),
                        store.previous(conch),
                        store.installed(conch)));
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "conch prune failed conch={0}", conch);
            }
            Set<String> removed = change.map(CredentialDeclarationChange::removed).orElseGet(Set::of);
            deleteRemovedFields(conch, verified.conchDoc(), removed);
            // Clear only after the new doc is in the registry. In-flight requests keep the snapshot
            // they already authorized (audit A01) and are not switched onto this version.
            releaseReefDisable(conch);
            store.clearRejected(conch);
            return new InstallResult(outcome(entry, OutcomeKind.INSTALLED, null), change);
        }
    }

    /**
     * Reef left this subscribed official conch out of the manifest. Record the tombstone once
     * and leave the registry entry and {@code current} in place, so calls keep using the
     * installed snapshot (audit A01). This path does not download or install another version.
     */
    private void deactivate(String conch, List<ConchOutcome> conches) {
        if (isLocal(conch)) {
            return;
        }
        if (!ConchFiles.safeSegment(conch)) {
            String version = registry.find(conch).map(doc -> doc.version().toString()).orElse(null);
            try {
                noteReefDisabled(conch, version);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "disable tombstone failed conch={0}", conch);
                conches.add(new ConchOutcome(conch, version, OutcomeKind.REJECTED, SyncReasons.DISABLE_FAILED));
                return;
            }
            conches.add(new ConchOutcome(conch, version, OutcomeKind.DISABLED, SyncReasons.DISABLED));
            return;
        }
        synchronized (ConchFileLocks.of(conch)) {
            if (isLocal(conch)) {
                return;
            }
            String version = ConchFiles.readCurrent(paths, conch)
                    .orElseGet(() -> registry.find(conch).map(doc -> doc.version().toString()).orElse(null));
            try {
                noteReefDisabled(conch, version);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "disable tombstone failed conch={0}", conch);
                conches.add(new ConchOutcome(conch, version, OutcomeKind.REJECTED, SyncReasons.DISABLE_FAILED));
                return;
            }
            conches.add(new ConchOutcome(conch, version, OutcomeKind.DISABLED, SyncReasons.DISABLED));
        }
    }

    /** First observation only. A later sync does not refresh {@code disabled_at} or write another audit row. */
    private void noteReefDisabled(String conch, String version) {
        if (disabledConches.contains(conch)) {
            return;
        }
        disabledConches.mark(conch, version, clock.instant());
        AuditLog.record(dataSource, Actors.SYNC, AuditKind.REEF_CONCH_DISABLED, "OFFICIAL/" + conch, conch);
    }

    /** Reef listed the conch again. One audit row, then the forward-only compare runs. */
    private void releaseReefDisable(String conch) {
        if (conch == null || !disabledConches.contains(conch)) {
            return;
        }
        disabledConches.clear(conch);
        AuditLog.record(dataSource, Actors.SYNC, AuditKind.REEF_CONCH_ENABLED, "OFFICIAL/" + conch, conch);
    }

    private InstallResult alignCurrent(ReefClient.CatalogEntry entry) {
        String conch = entry.conch();
        if (!ConchFiles.safeSegment(conch)) {
            return InstallResult.of(rejected(entry, SyncReasons.PATH_UNSAFE));
        }
        synchronized (ConchFileLocks.of(conch)) {
            if (isLocal(conch)) {
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_LOCAL, SyncReasons.LOCAL_KEPT));
            }
            ConchDoc doc = registry.find(conch).orElse(null);
            boolean registryMatches = doc != null && entry.version().equals(doc.version().toString());
            Optional<String> pointer = ConchFiles.readCurrent(paths, conch);
            boolean pointerMatches = pointer.isPresent() && entry.version().equals(pointer.get());
            if (registryMatches && !pointerMatches) {
                try {
                    ConchFiles.writeCurrent(paths, conch, entry.version());
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "current pointer write failed conch={0}", conch);
                    return InstallResult.of(rejected(entry, SyncReasons.CURRENT_WRITE_FAILED));
                }
            } else if (!registryMatches) {
                if (!pointerMatches || !loadInstalled(conch, entry.version())) {
                    return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
                }
            }
            releaseReefDisable(conch);
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
        }
    }

    private Fetched fetchVerified(ReefClient.CatalogEntry entry) {
        ReefClient.Download download = reef.download(entry.conch(), entry.version());
        if (download.tooLarge()) {
            return Fetched.rejected(rejected(entry, SyncReasons.ZIP_TOO_LARGE));
        }
        if (download.body() == null) {
            return Fetched.rejected(rejected(entry, SyncReasons.http(download.status())));
        }
        VerifiedConch verified;
        try {
            verified = ConchPackage.readVerified(download.body(), settings().reefKey());
        } catch (PackageVerificationException ex) {
            return Fetched.rejected(rejected(entry, ex.reason().name()));
        }
        ConchManifest manifest = verified.manifest();
        if (!manifest.conch().equals(entry.conch())
                || !manifest.version().equals(entry.version())
                || !manifest.sha256().equals(entry.sha256())) {
            return Fetched.rejected(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        return Fetched.ok(verified, download.body());
    }

    private Optional<ConchDoc> readStagedDeclaration(String conch) {
        if (conch == null || !ConchValidator.isConchName(conch) || isLocal(conch) || !ConchFiles.safeSegment(conch)) {
            return Optional.empty();
        }
        Optional<ReefClient.CatalogEntry> cached = cached(conch);
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        ReefClient.CatalogEntry entry = cached.get();
        String latest = entry.version();
        if (latest == null || !ConchFiles.safeSegment(latest) || entry.sha256() == null) {
            return Optional.empty();
        }
        Optional<SemVer> latestSem = version(latest);
        Optional<SemVer> installed = version(installedVersionText(conch));
        if (latestSem.isEmpty() || installed.isEmpty() || latestSem.get().compareTo(installed.get()) <= 0) {
            return Optional.empty();
        }
        try {
            java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, latest).resolve("package.zip");
            if (!Files.isRegularFile(zip) || Files.isSymbolicLink(zip) || Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                return Optional.empty();
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings().reefKey());
            ConchManifest manifest = verified.manifest();
            if (!conch.equals(manifest.conch())
                    || !latest.equals(manifest.version())
                    || !entry.sha256().equals(manifest.sha256())) {
                return Optional.empty();
            }
            return Optional.of(verified.conchDoc());
        } catch (RuntimeException | IOException ex) {
            return Optional.empty();
        }
    }

    private boolean officiallyLoaded(String conch) {
        return conch != null && registry.origin(conch) != Origin.LOCAL && registry.find(conch).isPresent();
    }

    private boolean previouslyInstalled(String conch) {
        return conch != null && !store.installed(conch).isEmpty();
    }

    /** Subscribed install record, not loaded, and not a Reef disable tombstone. */
    private boolean unloadedPreviousInstall(String conch) {
        return conch != null
                && !disabledConches.contains(conch)
                && !isLocal(conch)
                && !officiallyLoaded(conch)
                && previouslyInstalled(conch);
    }

    private String newestInstalled(String conch) {
        List<SyncStore.Installed> rows = store.installed(conch);
        return rows.isEmpty() ? null : rows.get(0).version();
    }

    private String unloadedReason(String conch) {
        if (!ConchFiles.safeSegment(conch)) {
            return SyncReasons.PATH_UNSAFE;
        }
        Optional<String> pointer = ConchFiles.readCurrent(paths, conch);
        if (pointer.isEmpty()) {
            return SyncReasons.POINTER_MISSING;
        }
        if (version(pointer.get()).isEmpty()) {
            return SyncReasons.VERSION_INVALID;
        }
        return localVerifyFailure(conch, pointer.get()).orElse(SyncReasons.NOT_LOADED);
    }

    private String repairVersion(String conch, String reason) {
        if (SyncReasons.POINTER_MISSING.equals(reason)
                || SyncReasons.VERSION_INVALID.equals(reason)
                || SyncReasons.PATH_UNSAFE.equals(reason)) {
            return semverOrNull(newestInstalled(conch));
        }
        Optional<String> pointer = ConchFiles.readCurrent(paths, conch);
        if (pointer.isPresent() && version(pointer.get()).isPresent()) {
            return pointer.get();
        }
        return semverOrNull(newestInstalled(conch));
    }

    private static String semverOrNull(String text) {
        if (text == null) {
            return null;
        }
        return version(text).isPresent() ? text : null;
    }

    /** Empty when the zip verifies as this conch and version. Otherwise a sync reason. */
    private Optional<String> localVerifyFailure(String conch, String versionText) {
        if (!ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(versionText)) {
            return Optional.of(SyncReasons.PATH_UNSAFE);
        }
        try {
            java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, versionText).resolve("package.zip");
            if (Files.isSymbolicLink(zip) || !Files.isRegularFile(zip)) {
                return Optional.of(SyncReasons.PACKAGE_MISSING);
            }
            if (Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                return Optional.of(SyncReasons.ZIP_TOO_LARGE);
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings().reefKey());
            if (!conch.equals(verified.manifest().conch()) || !versionText.equals(verified.manifest().version())) {
                return Optional.of(SyncReasons.MANIFEST_ENTRY_MISMATCH);
            }
            return Optional.empty();
        } catch (PackageVerificationException ex) {
            return Optional.of(ex.reason().name());
        } catch (RuntimeException | IOException ex) {
            return Optional.of(SyncReasons.PACKAGE_MISSING);
        }
    }

    /** Loads a verified installed zip and points {@code current} at it. Does not download. */
    private Reload reloadVerified(String conch, String versionText) {
        synchronized (ConchFileLocks.of(conch)) {
            if (isLocal(conch) || localVerifyFailure(conch, versionText).isPresent()) {
                return Reload.other();
            }
            VerifiedConch verified;
            try {
                java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, versionText).resolve("package.zip");
                verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings().reefKey());
            } catch (RuntimeException | IOException ex) {
                if (!isLocal(conch)) {
                    registry.remove(conch);
                }
                return Reload.other();
            }
            try {
                registry.put(verified.conchDoc(), Origin.OFFICIAL);
            } catch (ConchRejectedException ex) {
                String yaml = new String(verified.conchYaml(), StandardCharsets.UTF_8);
                return Reload.validation(RuleDisabled.explain(ImportIssueLines.locate(yaml, ex.issues())));
            } catch (RuntimeException ex) {
                if (!isLocal(conch)) {
                    registry.remove(conch);
                }
                return Reload.other();
            }
            try {
                ConchFiles.writeCurrent(paths, conch, versionText);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "current pointer write failed conch={0}", conch);
                if (!isLocal(conch)) {
                    registry.remove(conch);
                }
                return Reload.writeFailed();
            }
            store.clearRepairNotice(conch);
            return Reload.loaded();
        }
    }

    private String validationDisableMessage(String conch, String version) {
        VerifiedConch verified = readInstalled(conch, version);
        if (verified == null) {
            return null;
        }
        List<ValidationIssue> issues = registry.inspect(verified.conchDoc());
        if (issues.isEmpty()) {
            return null;
        }
        return RuleDisabled.explain(ImportIssueLines.locate(
                new String(verified.conchYaml(), StandardCharsets.UTF_8), issues));
    }

    private VerifiedConch readInstalled(String conch, String version) {
        if (conch == null || version == null || !ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return null;
        }
        try {
            java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
            if (Files.isSymbolicLink(zip) || !Files.isRegularFile(zip) || Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                return null;
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings().reefKey());
            if (!conch.equals(verified.manifest().conch()) || !version.equals(verified.manifest().version())) {
                return null;
            }
            return verified;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private enum ReloadKind {
        LOADED,
        VALIDATION,
        WRITE_FAILED,
        OTHER
    }

    private record Reload(ReloadKind kind, String explanation) {
        static Reload loaded() {
            return new Reload(ReloadKind.LOADED, null);
        }

        static Reload validation(String explanation) {
            return new Reload(ReloadKind.VALIDATION, explanation);
        }

        static Reload writeFailed() {
            return new Reload(ReloadKind.WRITE_FAILED, null);
        }

        static Reload other() {
            return new Reload(ReloadKind.OTHER, null);
        }
    }

    private void noteRepair(String conch, String reason) {
        String safe = reason != null && reason.matches("[A-Z0-9_]+") ? reason : "SYNC_FAILED";
        if (safe.equals(store.repairNotice(conch).orElse(null))) {
            return;
        }
        AuditLog.record(
                dataSource,
                Actors.SYNC,
                AuditKind.CONCH_REPAIR_REQUIRED,
                "OFFICIAL/" + conch,
                "{\"reason\":\"" + safe + "\"}");
        store.saveRepairNotice(conch, safe);
        LOG.log(Level.WARNING, "conch needs repair conch={0} reason={1}", new Object[] {conch, safe});
    }

    private String installedVersionText(String conch) {
        if (conch == null) {
            return null;
        }
        ConchDoc doc = registry.find(conch).orElse(null);
        if (doc != null && registry.origin(conch) != Origin.LOCAL) {
            return doc.version().toString();
        }
        if (!ConchFiles.safeSegment(conch)) {
            return null;
        }
        return ConchFiles.readCurrent(paths, conch).filter(value -> version(value).isPresent()).orElse(null);
    }

    private boolean minCrabAllows(String minCrabVersion) {
        Optional<SemVer> min = version(minCrabVersion);
        return min.isPresent() && min.get().compareTo(crabVersion) <= 0;
    }

    private boolean higherInstallable(ReefClient.CatalogEntry entry, String installedVersion) {
        if (installedVersion == null || entry.version() == null || !minCrabAllows(entry.minCrabVersion())) {
            return false;
        }
        Optional<SemVer> manifest = version(entry.version());
        Optional<SemVer> installed = version(installedVersion);
        return manifest.isPresent() && installed.isPresent() && manifest.get().compareTo(installed.get()) > 0;
    }

    private List<String> missingOnDefaultAccount(String conch, List<String> newly) {
        if (newly == null || newly.isEmpty()) {
            return List.of();
        }
        String account;
        try {
            account = new AccountStore(dataSource).resolve(Origin.OFFICIAL, conch, Optional.empty()).account();
        } catch (AccountNotFoundException ex) {
            if (ex.account() != null) {
                throw ex;
            }
            return List.of();
        }
        Set<String> present = vault.fieldNames(Origin.OFFICIAL, conch, account);
        List<String> missing = new ArrayList<>();
        for (String name : newly) {
            if (!present.contains(name)) {
                missing.add(name);
            }
        }
        return List.copyOf(missing);
    }

    private static List<String> newlyRequired(ConchDoc installed, List<String> manifestNames) {
        if (installed == null || manifestNames == null || manifestNames.isEmpty()) {
            return List.of();
        }
        Set<String> already = Set.copyOf(requiredNames(installed));
        List<String> newly = new ArrayList<>();
        for (String name : manifestNames) {
            if (name != null && !already.contains(name)) {
                newly.add(name);
            }
        }
        newly.sort(String::compareTo);
        return List.copyOf(newly);
    }

    private static List<String> requiredNames(ConchDoc doc) {
        if (doc == null || doc.credentials() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, CredentialField> field : doc.credentials().entrySet()) {
            if (field.getKey() != null && field.getValue() != null && field.getValue().required()) {
                names.add(field.getKey());
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static List<String> sorted(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(names);
        copy.sort(String::compareTo);
        return List.copyOf(copy);
    }

    private static UpdateException updateRejected(String reason) {
        String message = reason == null || reason.isBlank() ? SyncReasons.MANIFEST_ENTRY_MISMATCH : reason;
        return new UpdateException(409, ErrorCode.INVALID_INPUT, message);
    }

    private boolean loadInstalled(String conch, String version) {
        try {
            java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
            if (!Files.isRegularFile(zip) || Files.isSymbolicLink(zip) || Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                return false;
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings().reefKey());
            if (!conch.equals(verified.manifest().conch()) || !version.equals(verified.manifest().version())) {
                return false;
            }
            if (isLocal(conch)) {
                return false;
            }
            registry.put(verified.conchDoc(), Origin.OFFICIAL);
            return true;
        } catch (RuntimeException | IOException ex) {
            if (!isLocal(conch)) {
                registry.remove(conch);
            }
            return false;
        }
    }

    private void retryPendingCredentialDeletes() {
        for (String conch : store.conchesWithPendingCredentialDeletes()) {
            deleteRemovedFields(conch, registry.find(conch).orElse(null), Set.of());
        }
    }

    private void deleteRemovedFields(String conch, ConchDoc installed, Set<String> extra) {
        Set<String> fields = new TreeSet<>(store.pendingCredentialFields(conch));
        if (extra != null) {
            fields.addAll(extra);
        }
        if (installed != null && installed.credentials() != null) {
            fields.removeAll(installed.credentials().keySet());
        }
        fields.remove(McpOAuthToken.FIELD);
        if (fields.isEmpty()) {
            store.replacePendingCredentialFields(conch, Set.of());
            return;
        }
        try {
            deleteOfficialFields(conch, fields);
            store.replacePendingCredentialFields(conch, Set.of());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "credential delete failed conch={0} count={1}", new Object[] {conch, fields.size()});
            store.replacePendingCredentialFields(conch, fields);
        }
    }

    private void deleteOfficialFields(String conch, Set<String> fields) {
        if (credentialDeleteFailures > 0) {
            credentialDeleteFailures--;
            throw new IllegalStateException("credential delete failed");
        }
        vault.deleteFields(Origin.OFFICIAL, conch, fields);
    }

    private static Optional<SemVer> version(String text) {
        try {
            return Optional.of(SemVer.parse(text));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * 注册表里已有的文档优先。没有时只从 {@code current} 指向的 package.zip 验签读取。
     * 验签失败、不是普通文件、超过大小上限、路径上有符号链接，或身份对不上时返回 null，
     * 不用旁边未签名的 conch.yaml 删凭证，也不把它放回注册表。
     */
    private ConchDoc previousDoc(String conch) {
        Optional<ConchDoc> live = registry.find(conch);
        if (live.isPresent()) {
            return live.get();
        }
        Optional<String> current = ConchFiles.readCurrent(paths, conch);
        if (current.isEmpty() || !ConchFiles.safeSegment(conch)) {
            return null;
        }
        try {
            Path zip = ConchFiles.versionDir(paths, conch, current.get()).resolve("package.zip");
            VerifiedConch verified = ConchPackage.readVerified(ConchFiles.readZip(paths, zip), settings().reefKey());
            if (!conch.equals(verified.manifest().conch()) || !current.get().equals(verified.manifest().version())) {
                return null;
            }
            return verified.conchDoc();
        } catch (RuntimeException | IOException ex) {
            return null;
        }
    }

    private void registryRestore(String conch, ConchDoc previous, Origin origin) {
        if (isLocal(conch)) {
            return;
        }
        if (previous == null) {
            registry.remove(conch);
            return;
        }
        try {
            registry.put(previous, origin == null ? Origin.OFFICIAL : origin);
        } catch (RuntimeException ex) {
            if (!isLocal(conch)) {
                registry.remove(conch);
            }
        }
    }

    private boolean isLocal(String conch) {
        return registry.origin(conch) == Origin.LOCAL;
    }

    private String redact(String message) {
        if (message == null || message.isBlank()) {
            return "conch sync failed";
        }
        String token = settings().token().orElse(null);
        if (token != null && message.contains(token)) {
            return message.replace(token, "[redacted]");
        }
        return message;
    }

    private static String safeReason(RuntimeException ex) {
        if (ex instanceof PackageVerificationException packageError) {
            return packageError.reason().name();
        }
        if (ex instanceof SyncException) {
            return ex.getMessage() == null ? "SYNC_FAILED" : ex.getMessage();
        }
        return "SYNC_FAILED";
    }

    private static String registryReason(ConchRejectedException ex) {
        StringBuilder codes = new StringBuilder(SyncReasons.REGISTRY_REJECTED);
        for (ValidationIssue issue : ex.issues()) {
            codes.append(':').append(issue.code());
        }
        return codes.toString();
    }

    private static ConchOutcome outcome(ReefClient.CatalogEntry entry, OutcomeKind kind, String reason) {
        return new ConchOutcome(entry.conch(), entry.version(), kind, reason);
    }

    private static ConchOutcome rejected(ReefClient.CatalogEntry entry, String reason) {
        return outcome(entry, OutcomeKind.REJECTED, reason);
    }

    private record InstallResult(ConchOutcome outcome, Optional<CredentialDeclarationChange> change) {
        static InstallResult of(ConchOutcome outcome) {
            return new InstallResult(outcome, Optional.empty());
        }
    }

    private static final class Fetched {
        private final VerifiedConch verified;
        private final byte[] zip;
        private final ConchOutcome rejected;

        private Fetched(VerifiedConch verified, byte[] zip, ConchOutcome rejected) {
            this.verified = verified;
            this.zip = zip;
            this.rejected = rejected;
        }

        static Fetched ok(VerifiedConch verified, byte[] zip) {
            return new Fetched(verified, zip, null);
        }

        static Fetched rejected(ConchOutcome outcome) {
            return new Fetched(null, null, outcome);
        }

        VerifiedConch verified() {
            return verified;
        }

        byte[] zip() {
            return zip;
        }

        ConchOutcome rejected() {
            return rejected;
        }
    }
}
