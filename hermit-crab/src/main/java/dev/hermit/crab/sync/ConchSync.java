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
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchManifest;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.version.SemVer;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

public final class ConchSync {

    private static final Logger LOG = Logger.getLogger(ConchSync.class.getName());

    private final SyncSettings settings;
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
        this.settings = settings;
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
        this.reef = new ReefClient(settings, client);
    }

    public DisabledConches disabledConches() {
        return disabledConches;
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
            retryClearDisabledCurrents();
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
     * Console view of {@code last}. While a rollback pin matches the current pointer, that conch
     * is shown as the installed version with {@link OutcomeKind#ROLLED_BACK}. Reads the pin and
     * current file under {@link ConchFileLocks} and does not touch the registry, so it does not
     * open another version-switch window beside audit A01.
     */
    public SyncReport visibleStatus(SyncReport last) {
        if (last == null) {
            return null;
        }
        return projectDisabled(projectRollbacks(last));
    }

    private SyncReport projectRollbacks(SyncReport last) {
        LinkedHashMap<String, ConchOutcome> rows = new LinkedHashMap<>();
        for (ConchOutcome outcome : last.conches()) {
            if (outcome.conch() != null) {
                rows.put(outcome.conch(), outcome);
            }
        }
        boolean changed = false;
        for (SyncStore.PinRow pin : store.pins()) {
            ConchOutcome current = rows.get(pin.conch());
            ConchOutcome projected = projectRollback(pin.conch(), current);
            if (projected != current) {
                changed = true;
                rows.put(pin.conch(), projected);
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(false, List.copyOf(rows.values()), last.removed(), last.credentialChanges(),
                last.latestCrab(), last.error());
    }

    /**
     * A later 304 replaces the conch rows. Tombstones are reapplied here so the console still
     * shows the conch as disabled, without touching the registry (audit A01).
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
            if (removed.add(conch)) {
                changed = true;
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(last.notModified(), List.copyOf(rows.values()), List.copyOf(removed),
                last.credentialChanges(), last.latestCrab(), last.error());
    }

    private ConchOutcome projectRollback(String conch, ConchOutcome outcome) {
        if (!ConchFiles.safeSegment(conch)) {
            return outcome;
        }
        synchronized (ConchFileLocks.of(conch)) {
            Optional<SyncStore.Pin> pin = store.pin(conch);
            if (pin.isEmpty()) {
                return outcome;
            }
            Optional<String> installed = ConchFiles.readCurrent(paths, conch);
            if (installed.isEmpty() || !installed.get().equals(pin.get().rolledBackTo())) {
                return outcome;
            }
            ConchOutcome rolled = new ConchOutcome(conch, installed.get(), OutcomeKind.ROLLED_BACK, RollbackGuard.REASON);
            if (rolled.equals(outcome)) {
                return outcome;
            }
            return rolled;
        }
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
                if (!version(entry.version()).isPresent()) {
                    conches.add(rejected(entry, SyncReasons.VERSION_INVALID));
                    continue;
                }
                if (!subscriptions.contains(entry.conch())) {
                    conches.add(new ConchOutcome(entry.conch(), entry.version(), OutcomeKind.NOT_SUBSCRIBED, null));
                    continue;
                }
                if (registry.origin(entry.conch()) == Origin.LOCAL) {
                    LOG.log(Level.INFO, "skip reef install for local conch={0}", entry.conch());
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
        // and leave Origin.LOCAL alone. Section C's local conches keep both checks.
        Map<String, ConchDoc> loaded = registry.snapshot();
        for (String conch : loaded.keySet()) {
            if (present.contains(conch)) {
                continue;
            }
            if (!subscriptions.contains(conch) || registry.origin(conch) == Origin.LOCAL) {
                continue;
            }
            deactivate(conch, conches, removed);
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
            if (outcome.kind() != OutcomeKind.INSTALLED && outcome.kind() != OutcomeKind.SKIPPED_SAME) {
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
        if (cached.isPresent()) {
            for (ReefClient.CatalogEntry entry : cached.get()) {
                rows.add(catalogRow(entry));
            }
            rows.sort(Comparator.comparing(ConchCatalog.Entry::conch, Comparator.nullsLast(String::compareTo)));
        }
        return new ConchCatalog(true, store.catalogSyncedAt().orElse(null), rows);
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
        return new ConchCatalog.Entry(
                name,
                entry.version(),
                title,
                entry.description(),
                entry.capabilityCount(),
                name != null && subscriptions.contains(name),
                doc != null || pointer != null,
                installedVersion,
                name != null && disabledConches.contains(name),
                name != null && registry.origin(name) == Origin.LOCAL);
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
        long delay = Math.max(1L, settings.interval().toMillis());
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
        SemVer incoming = parsed.get();
        if (entry.sha256() == null || entry.minCrabVersion() == null) {
            return InstallResult.of(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        Optional<SyncStore.Pin> pin = store.pin(entry.conch());
        if (pin.isPresent()) {
            SemVer bad;
            try {
                bad = SemVer.parse(pin.get().badVersion());
            } catch (IllegalArgumentException ex) {
                bad = null;
            }
            if (bad != null && incoming.compareTo(bad) <= 0) {
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_PINNED, SyncReasons.PINNED));
            }
        }
        SemVer min;
        try {
            min = SemVer.parse(entry.minCrabVersion());
        } catch (IllegalArgumentException ex) {
            return InstallResult.of(rejected(entry, SyncReasons.VERSION_INVALID));
        }
        if (min.compareTo(crabVersion) > 0) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        Optional<String> current = ConchFiles.readCurrent(paths, entry.conch());
        if (current.isPresent() && current.get().equals(entry.version())) {
            InstallResult kept = keepCurrent(entry);
            if (kept != null) {
                return kept;
            }
        }
        ReefClient.Download download = reef.download(entry.conch(), entry.version());
        if (download.tooLarge()) {
            return InstallResult.of(rejected(entry, SyncReasons.ZIP_TOO_LARGE));
        }
        if (download.body() == null) {
            return InstallResult.of(rejected(entry, SyncReasons.http(download.status())));
        }
        VerifiedConch verified;
        try {
            verified = ConchPackage.readVerified(download.body(), settings.reefKey());
        } catch (PackageVerificationException ex) {
            return InstallResult.of(rejected(entry, ex.reason().name()));
        }
        ConchManifest manifest = verified.manifest();
        if (!manifest.conch().equals(entry.conch())
                || !manifest.version().equals(entry.version())
                || !manifest.sha256().equals(entry.sha256())) {
            return InstallResult.of(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        if (verified.conchDoc().minCrabVersion().compareTo(crabVersion) > 0) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        return activate(entry, verified, download.body(), current.orElse(null), pin.isPresent());
    }

    private InstallResult activate(
            ReefClient.CatalogEntry entry,
            VerifiedConch verified,
            byte[] zip,
            String previousCurrent,
            boolean clearPin
    ) {
        String conch = entry.conch();
        String version = entry.version();
        synchronized (ConchFileLocks.of(conch)) {
            Optional<SyncStore.Pin> pinNow = store.pin(conch);
            if (pinNow.isPresent() && !newerThanPin(version, pinNow.get())) {
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_PINNED, SyncReasons.PINNED));
            }
            Optional<String> currentNow = ConchFiles.readCurrent(paths, conch);
            if (currentNow.isPresent() && currentNow.get().equals(version)) {
                if (registry.find(conch).isEmpty() && !loadInstalled(conch, version)) {
                    return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
                }
                disabledConches.clear(conch);
                if (pinNow.isPresent()) {
                    store.clearPin(conch);
                }
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
            }
            ConchDoc previous = previousDoc(conch);
            Optional<CredentialDeclarationChange> change = CredentialChanges.between(previous, verified.conchDoc());
            ConchFiles.install(paths, conch, version, zip, verified.conchYaml());
            try {
                registry.put(verified.conchDoc());
            } catch (ConchRejectedException ex) {
                ConchFiles.deleteVersion(paths, conch, version);
                return InstallResult.of(rejected(entry, registryReason(ex)));
            } catch (RuntimeException ex) {
                ConchFiles.deleteVersion(paths, conch, version);
                registryRestore(conch, previous);
                return InstallResult.of(rejected(entry, SyncReasons.REGISTRY_REJECTED));
            }
            try {
                ConchFiles.writeCurrent(paths, conch, version);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "current pointer write failed conch={0}", conch);
                registryRestore(conch, previous);
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
            if (clearPin || pinNow.isPresent()) {
                store.clearPin(conch);
            }
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
            // Tombstone drops only after the new doc is in the registry, so a call cannot observe
            // "enabled" against an empty registry. In-flight requests keep the snapshot they already
            // authorized (audit A01) and are not switched onto this version.
            disabledConches.clear(conch);
            return new InstallResult(outcome(entry, OutcomeKind.INSTALLED, null), change);
        }
    }

    /**
     * Reef left this conch out of the manifest. Record the tombstone before dropping the registry
     * entry so new calls return {@code CONCH_DISABLED} instead of a bare not-found. The authorized
     * snapshot of a request already inside the engine is left alone (audit A01); this path does
     * not install a replacement version.
     */
    private void deactivate(String conch, List<ConchOutcome> conches, List<String> removed) {
        if (!ConchFiles.safeSegment(conch)) {
            disabledConches.mark(conch, null, clock.instant());
            registry.remove(conch);
            removed.add(conch);
            conches.add(new ConchOutcome(conch, null, OutcomeKind.DISABLED, SyncReasons.DISABLED));
            return;
        }
        synchronized (ConchFileLocks.of(conch)) {
            String version = ConchFiles.readCurrent(paths, conch).orElse(null);
            try {
                disabledConches.mark(conch, version, clock.instant());
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "disable tombstone failed conch={0}", conch);
                conches.add(new ConchOutcome(conch, version, OutcomeKind.REJECTED, SyncReasons.DISABLE_FAILED));
                return;
            }
            registry.remove(conch);
            try {
                ConchFiles.clearCurrent(paths, conch);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "clear current failed conch={0}", conch);
            }
            removed.add(conch);
            conches.add(new ConchOutcome(conch, version, OutcomeKind.DISABLED, SyncReasons.DISABLED));
        }
    }

    private InstallResult keepCurrent(ReefClient.CatalogEntry entry) {
        String conch = entry.conch();
        if (!ConchFiles.safeSegment(conch)) {
            return InstallResult.of(rejected(entry, SyncReasons.PATH_UNSAFE));
        }
        synchronized (ConchFileLocks.of(conch)) {
            Optional<String> currentNow = ConchFiles.readCurrent(paths, conch);
            if (currentNow.isEmpty() || !currentNow.get().equals(entry.version())) {
                return null;
            }
            if (registry.find(conch).isEmpty() && !loadInstalled(conch, entry.version())) {
                return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
            }
            disabledConches.clear(conch);
            if (store.pin(conch).isPresent()) {
                store.clearPin(conch);
            }
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
        }
    }

    private boolean loadInstalled(String conch, String version) {
        try {
            java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
            if (!Files.isRegularFile(zip) || Files.isSymbolicLink(zip) || Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                return false;
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), settings.reefKey());
            if (!conch.equals(verified.manifest().conch()) || !version.equals(verified.manifest().version())) {
                return false;
            }
            registry.put(verified.conchDoc());
            return true;
        } catch (RuntimeException | IOException ex) {
            registry.remove(conch);
            return false;
        }
    }

    private void retryClearDisabledCurrents() {
        for (String conch : disabledConches.names()) {
            if (!ConchFiles.safeSegment(conch)) {
                continue;
            }
            synchronized (ConchFileLocks.of(conch)) {
                if (!disabledConches.contains(conch)) {
                    continue;
                }
                try {
                    ConchFiles.clearCurrent(paths, conch);
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "clear current failed conch={0}", conch);
                }
            }
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

    private static boolean newerThanPin(String version, SyncStore.Pin pin) {
        try {
            return SemVer.parse(version).compareTo(SemVer.parse(pin.badVersion())) > 0;
        } catch (IllegalArgumentException ex) {
            return false;
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

    private void registryRestore(String conch, ConchDoc previous) {
        if (previous == null) {
            registry.remove(conch);
            return;
        }
        try {
            registry.put(previous);
        } catch (RuntimeException ex) {
            registry.remove(conch);
        }
    }

    private String redact(String message) {
        if (message == null || message.isBlank()) {
            return "conch sync failed";
        }
        String token = settings.token().orElse(null);
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
}
