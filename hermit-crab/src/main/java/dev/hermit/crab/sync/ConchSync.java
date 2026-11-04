package dev.hermit.crab.sync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
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
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

public final class ConchSync {

    private static final Logger LOG = Logger.getLogger(ConchSync.class.getName());

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
        this.client = HttpClient.newBuilder()
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
        synchronized (this) {
            settings.set(Objects.requireNonNull(next, "settings"));
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
        synchronized (this) {
            return syncOnceLocked();
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
            ex.clientError().ifPresent(error -> deliver(SyncReport.failed(error)));
            throw ex;
        }
    }

    public ConchCatalog catalog() {
        synchronized (this) {
            return readCatalog();
        }
    }

    /**
     * Subscribes, then syncs under this lock so a 304 cannot skip the install.
     * {@code onEachReport} sees a prefetch when the catalog cache is absent, and the install sync.
     * The caller writes one {@code SYNC_REQUESTED} audit after this returns.
     */
    public ConchOutcome subscribe(String conch, String actor, Consumer<SyncReport> onEachReport) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        synchronized (this) {
            if (!ConchValidator.isConchName(conch)) {
                throw new SubscriptionException(400, ErrorCode.INVALID_INPUT, SubscriptionException.NAME_INVALID);
            }
            if (registry.origin(conch) == Origin.LOCAL) {
                throw new SubscriptionException(409, ErrorCode.INVALID_INPUT, SubscriptionException.LOCAL_CONFLICT);
            }
            if (store.catalog().isEmpty()) {
                SyncReport prefetched = syncOnceLocked();
                if (onEachReport != null && prefetched != null) {
                    onEachReport.accept(prefetched);
                }
            }
            if (cached(conch).isEmpty()) {
                throw new SubscriptionException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_IN_CATALOG);
            }
            if (subscriptions.record(conch, actor, clock.instant())) {
                AuditLog.record(dataSource, actor, AuditKind.CONCH_SUBSCRIBED, "OFFICIAL/" + conch, conch);
            }
            store.clearEtagAndSnapshot();
            SyncReport report = syncOnceLocked();
            if (onEachReport != null && report != null) {
                onEachReport.accept(report);
            }
            return report == null ? null : outcomeFor(report, conch);
        }
    }

    public void unsubscribe(String conch, String actor) {
        SubscriptionCommands.unsubscribe(
                this, paths, registry, disabledConches, subscriptions, dataSource, conch, actor);
    }

    /**
     * Installs the last synced catalog version when it is strictly higher than the installed one.
     * Holds this lock so it does not race {@link #syncOnce()}. Does not refetch the manifest.
     * When the default account lacks a newly required credential, the verified zip is stored under
     * the version directory and the current pointer stays put.
     */
    public UpdateResult update(String conch, String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
        }
        synchronized (this) {
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
                throw new UpdateException(409, ErrorCode.CONCH_DISABLED, DisabledConches.CALL_MESSAGE);
            }
            ReefClient.CatalogEntry entry = cached(conch).orElseThrow(() ->
                    new UpdateException(404, ErrorCode.NOT_FOUND, SubscriptionException.NOT_IN_CATALOG));
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
            ConchDoc installedDoc = registry.find(conch).orElse(null);
            List<String> newly = installedDoc == null
                    ? requiredNames(verified.conchDoc())
                    : sorted(ConchDiff.between(installedDoc, verified.conchDoc()).newRequiredCredentials());
            List<String> missing = missingOnDefaultAccount(conch, newly);
            if (!missing.isEmpty()) {
                synchronized (ConchFileLocks.of(conch)) {
                    if (isLocal(conch)) {
                        throw new UpdateException(409, ErrorCode.INVALID_INPUT, UpdateException.LOCAL_CONFLICT);
                    }
                    ConchFiles.install(paths, conch, entry.version(), fetched.zip(), verified.conchYaml());
                }
                throw new UpdateException(
                        409,
                        ErrorCode.NOT_CONFIGURED,
                        UpdateException.MISSING_PREFIX + String.join(", ", missing),
                        missing);
            }
            InstallResult activated = activate(entry, verified, fetched.zip(), installedText);
            if (activated.outcome().kind() != OutcomeKind.INSTALLED) {
                throw updateRejected(activated.outcome().reason());
            }
            AuditLog.record(
                    dataSource,
                    actor,
                    AuditKind.CONCH_UPDATED,
                    "OFFICIAL/" + conch,
                    "{\"from\":\"" + installedText + "\",\"to\":\"" + entry.version() + "\"}");
            return new UpdateResult(activated.outcome(), activated.change(), installedText, entry.version());
        }
    }

    /**
     * Verified doc of a higher catalog version already stored on disk. Takes this lock.
     * Callers that already hold the lock must not call this method.
     */
    public Optional<ConchDoc> stagedDeclaration(String conch) {
        synchronized (this) {
            return readStagedDeclaration(conch);
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
        return projectDisabled(last);
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
                List.of());
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
        boolean installed = doc != null || pointer != null;
        List<String> newly = local || doc == null ? List.of() : newlyRequired(doc, entry.requiredCredentials());
        boolean updateAvailable = !local && !disabled && subscribed && installed
                && higherInstallable(entry, installedVersion);
        String latestVersion = local ? null : entry.version();
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
                local ? List.of() : newly);
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
            LOG.log(Level.WARNING, "conch sync failed: {0}", redact(ex.getMessage()));
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

    private InstallResult downloadAndActivate(ReefClient.CatalogEntry entry, String previousCurrent) {
        Fetched fetched = fetchVerified(entry);
        if (fetched.rejected() != null) {
            return InstallResult.of(fetched.rejected());
        }
        if (fetched.verified().conchDoc().minCrabVersion().compareTo(crabVersion) > 0) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        return activate(entry, fetched.verified(), fetched.zip(), previousCurrent);
    }

    private InstallResult activate(
            ReefClient.CatalogEntry entry,
            VerifiedConch verified,
            byte[] zip,
            String previousCurrent
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
                if (loaded == null) {
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
                ConchFiles.deleteVersion(paths, conch, version);
                return InstallResult.of(rejected(entry, registryReason(ex)));
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
            if (switchedFrom != null) {
                store.setPrevious(conch, switchedFrom);
            } else {
                store.clearPrevious(conch);
            }
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

    private ConchDoc previousDoc(String conch) {
        Optional<ConchDoc> live = registry.find(conch);
        if (live.isPresent()) {
            return live.get();
        }
        Optional<String> current = ConchFiles.readCurrent(paths, conch);
        if (current.isEmpty()) {
            return null;
        }
        java.nio.file.Path yaml = ConchFiles.versionDir(paths, conch, current.get()).resolve("conch.yaml");
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try {
            return ConchParser.parse(Files.readString(yaml));
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
