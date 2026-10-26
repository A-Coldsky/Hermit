package dev.hermit.crab.sync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
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

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ShellManifest;
import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.common.pkg.VerifiedShell;
import dev.hermit.common.version.SemVer;
import dev.hermit.common.validate.ShellValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.engine.ShellRejectedException;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;

public final class ShellSync {

    private static final Logger LOG = Logger.getLogger(ShellSync.class.getName());

    private final SyncSettings settings;
    private final CrabPaths paths;
    private final ShellRegistry registry;
    private final CredentialVault vault;
    private final SyncStore store;
    private final DisabledShells disabledShells;
    private final ReefClient reef;
    private final Clock clock;
    private final HttpClient client;
    private final SemVer crabVersion = CrabVersion.CURRENT;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Consumer<SyncReport> reportListener;
    private int credentialDeleteFailures;

    public ShellSync(
            SyncSettings settings,
            CrabPaths paths,
            ShellRegistry registry,
            CredentialVault vault,
            DataSource dataSource
    ) {
        this(settings, paths, registry, vault, dataSource, Clock.systemUTC());
    }

    public ShellSync(
            SyncSettings settings,
            CrabPaths paths,
            ShellRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock
    ) {
        this(settings, paths, registry, vault, dataSource, clock, new DisabledShells(dataSource), true);
    }

    public ShellSync(
            SyncSettings settings,
            CrabPaths paths,
            ShellRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock,
            DisabledShells disabledShells
    ) {
        this(settings, paths, registry, vault, dataSource, clock, disabledShells, false);
    }

    private ShellSync(
            SyncSettings settings,
            CrabPaths paths,
            ShellRegistry registry,
            CredentialVault vault,
            DataSource dataSource,
            Clock clock,
            DisabledShells disabledShells,
            boolean loadDisabled
    ) {
        this.settings = settings;
        this.paths = paths;
        this.registry = registry;
        this.vault = vault;
        this.store = new SyncStore(dataSource);
        this.disabledShells = disabledShells == null ? new DisabledShells(dataSource) : disabledShells;
        if (loadDisabled || disabledShells == null) {
            this.disabledShells.load();
        }
        this.clock = clock;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.reef = new ReefClient(settings, client);
    }

    public DisabledShells disabledShells() {
        return disabledShells;
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
            try {
                retryPendingCredentialDeletes();
                retryClearDisabledCurrents();
                Optional<CrabRelease> latest = readLatest();
                ReefClient.ManifestFetch manifest = reef.manifest(store.etag().orElse(null));
                if (manifest.notModified()) {
                    if (snapshotMatches()) {
                        if (manifest.etag() != null) {
                            store.saveEtag(manifest.etag());
                        }
                        return deliver(unchanged(latest));
                    }
                    // The stored tag no longer describes this disk. Drop it and read the full
                    // manifest once; a second 304 must not loop or be recorded.
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
    }

    /**
     * Console view of {@code last}. While a rollback pin matches the current pointer, that shell
     * is shown as the installed version with {@link OutcomeKind#ROLLED_BACK}. Reads the pin and
     * current file under {@link ShellFileLocks} and does not touch the registry, so it does not
     * open another version-switch window beside audit A01.
     */
    public SyncReport visibleStatus(SyncReport last) {
        if (last == null) {
            return null;
        }
        return projectDisabled(projectRollbacks(last));
    }

    private SyncReport projectRollbacks(SyncReport last) {
        LinkedHashMap<String, ShellOutcome> rows = new LinkedHashMap<>();
        for (ShellOutcome outcome : last.shells()) {
            if (outcome.shell() != null) {
                rows.put(outcome.shell(), outcome);
            }
        }
        boolean changed = false;
        for (SyncStore.PinRow pin : store.pins()) {
            ShellOutcome current = rows.get(pin.shell());
            ShellOutcome projected = projectRollback(pin.shell(), current);
            if (projected != current) {
                changed = true;
                rows.put(pin.shell(), projected);
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(false, List.copyOf(rows.values()), last.removed(), last.credentialChanges(),
                last.latestCrab(), last.error());
    }

    /**
     * A later 304 replaces the shell rows. Tombstones are reapplied here so the console still
     * shows the shell as disabled, without touching the registry (audit A01).
     */
    private SyncReport projectDisabled(SyncReport last) {
        List<String> names = new ArrayList<>(disabledShells.names());
        names.sort(String::compareTo);
        if (names.isEmpty()) {
            return last;
        }
        LinkedHashMap<String, ShellOutcome> rows = new LinkedHashMap<>();
        for (ShellOutcome outcome : last.shells()) {
            if (outcome.shell() != null) {
                rows.put(outcome.shell(), outcome);
            }
        }
        LinkedHashSet<String> removed = new LinkedHashSet<>(last.removed());
        boolean changed = false;
        for (String shell : names) {
            ShellOutcome current = rows.get(shell);
            if (current != null && (current.kind() == OutcomeKind.INSTALLED || current.kind() == OutcomeKind.SKIPPED_SAME)) {
                continue;
            }
            ShellOutcome projected = new ShellOutcome(shell, disabledShells.version(shell), OutcomeKind.DISABLED,
                    SyncReasons.DISABLED);
            if (!projected.equals(current)) {
                rows.put(shell, projected);
                changed = true;
            }
            if (removed.add(shell)) {
                changed = true;
            }
        }
        if (!changed) {
            return last;
        }
        return new SyncReport(last.notModified(), List.copyOf(rows.values()), List.copyOf(removed),
                last.credentialChanges(), last.latestCrab(), last.error());
    }

    private ShellOutcome projectRollback(String shell, ShellOutcome outcome) {
        if (!ShellFiles.safeSegment(shell)) {
            return outcome;
        }
        synchronized (ShellFileLocks.of(shell)) {
            Optional<SyncStore.Pin> pin = store.pin(shell);
            if (pin.isEmpty()) {
                return outcome;
            }
            Optional<String> installed = ShellFiles.readCurrent(paths, shell);
            if (installed.isEmpty() || !installed.get().equals(pin.get().rolledBackTo())) {
                return outcome;
            }
            ShellOutcome rolled = new ShellOutcome(shell, installed.get(), OutcomeKind.ROLLED_BACK, RollbackGuard.REASON);
            if (rolled.equals(outcome)) {
                return outcome;
            }
            return rolled;
        }
    }

    private SyncReport install(ReefClient.ManifestFetch manifest, Optional<CrabRelease> latest) {
        List<ShellOutcome> shells = new ArrayList<>();
        List<CredentialDeclarationChange> changes = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (ReefClient.CatalogEntry entry : manifest.entries()) {
            try {
                if (!ShellValidator.isShellName(entry.shell())) {
                    shells.add(rejected(entry, SyncReasons.SHELL_NAME));
                    continue;
                }
                present.add(entry.shell());
                if (!version(entry.version()).isPresent()) {
                    shells.add(rejected(entry, SyncReasons.VERSION_INVALID));
                    continue;
                }
                InstallResult result = installEntry(entry);
                shells.add(result.outcome());
                result.change().ifPresent(changes::add);
            } catch (RuntimeException ex) {
                shells.add(new ShellOutcome(entry.shell(), entry.version(), OutcomeKind.REJECTED, safeReason(ex)));
            }
        }
        List<String> removed = new ArrayList<>();
        for (String shell : registry.snapshot().keySet()) {
            if (!present.contains(shell)) {
                deactivate(shell, shells, removed);
            }
        }
        if (shells.stream().anyMatch(SyncFailures::retryable)) {
            store.clearEtagAndSnapshot();
        } else if (manifest.etag() != null) {
            store.saveEtagAndSnapshot(manifest.etag(), expectedVersions(shells));
        }
        return new SyncReport(false, shells, removed, changes, latest);
    }

    private boolean snapshotMatches() {
        Optional<Map<String, String>> snapshot = store.manifestSnapshot();
        if (snapshot.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : snapshot.get().entrySet()) {
            Optional<String> current = ShellFiles.readCurrent(paths, entry.getKey());
            if (current.isEmpty() || !current.get().equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> expectedVersions(List<ShellOutcome> shells) {
        Map<String, String> versions = new TreeMap<>();
        for (ShellOutcome outcome : shells) {
            if (outcome.kind() != OutcomeKind.INSTALLED && outcome.kind() != OutcomeKind.SKIPPED_SAME) {
                continue;
            }
            if (outcome.shell() == null || outcome.version() == null) {
                continue;
            }
            versions.put(outcome.shell(), outcome.version());
        }
        return versions;
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
            LOG.log(Level.WARNING, "shell sync failed: {0}", redact(ex.getMessage()));
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
        if (!ShellValidator.isShellName(entry.shell())) {
            return InstallResult.of(rejected(entry, SyncReasons.SHELL_NAME));
        }
        Optional<SemVer> parsed = version(entry.version());
        if (parsed.isEmpty()) {
            return InstallResult.of(rejected(entry, SyncReasons.VERSION_INVALID));
        }
        if (!ShellFiles.safeSegment(entry.version())) {
            return InstallResult.of(rejected(entry, SyncReasons.PATH_UNSAFE));
        }
        SemVer incoming = parsed.get();
        if (entry.sha256() == null || entry.minCrabVersion() == null) {
            return InstallResult.of(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        Optional<SyncStore.Pin> pin = store.pin(entry.shell());
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
        Optional<String> current = ShellFiles.readCurrent(paths, entry.shell());
        if (current.isPresent() && current.get().equals(entry.version())) {
            InstallResult kept = keepCurrent(entry);
            if (kept != null) {
                return kept;
            }
        }
        ReefClient.Download download = reef.download(entry.shell(), entry.version());
        if (download.tooLarge()) {
            return InstallResult.of(rejected(entry, SyncReasons.ZIP_TOO_LARGE));
        }
        if (download.body() == null) {
            return InstallResult.of(rejected(entry, SyncReasons.http(download.status())));
        }
        VerifiedShell verified;
        try {
            verified = ShellPackage.readVerified(download.body(), settings.reefKey());
        } catch (PackageVerificationException ex) {
            return InstallResult.of(rejected(entry, ex.reason().name()));
        }
        ShellManifest manifest = verified.manifest();
        if (!manifest.shell().equals(entry.shell())
                || !manifest.version().equals(entry.version())
                || !manifest.sha256().equals(entry.sha256())) {
            return InstallResult.of(rejected(entry, SyncReasons.MANIFEST_ENTRY_MISMATCH));
        }
        if (verified.shellDoc().minCrabVersion().compareTo(crabVersion) > 0) {
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_MIN_VERSION, SyncReasons.MIN_CRAB_VERSION));
        }
        return activate(entry, verified, download.body(), current.orElse(null), pin.isPresent());
    }

    private InstallResult activate(
            ReefClient.CatalogEntry entry,
            VerifiedShell verified,
            byte[] zip,
            String previousCurrent,
            boolean clearPin
    ) {
        String shell = entry.shell();
        String version = entry.version();
        synchronized (ShellFileLocks.of(shell)) {
            Optional<SyncStore.Pin> pinNow = store.pin(shell);
            if (pinNow.isPresent() && !newerThanPin(version, pinNow.get())) {
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_PINNED, SyncReasons.PINNED));
            }
            Optional<String> currentNow = ShellFiles.readCurrent(paths, shell);
            if (currentNow.isPresent() && currentNow.get().equals(version)) {
                if (registry.find(shell).isEmpty() && !loadInstalled(shell, version)) {
                    return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
                }
                disabledShells.clear(shell);
                if (pinNow.isPresent()) {
                    store.clearPin(shell);
                }
                return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
            }
            ShellDoc previous = previousDoc(shell);
            Optional<CredentialDeclarationChange> change = CredentialChanges.between(previous, verified.shellDoc());
            ShellFiles.install(paths, shell, version, zip, verified.shellYaml());
            try {
                registry.put(verified.shellDoc());
            } catch (ShellRejectedException ex) {
                ShellFiles.deleteVersion(paths, shell, version);
                return InstallResult.of(rejected(entry, registryReason(ex)));
            } catch (RuntimeException ex) {
                ShellFiles.deleteVersion(paths, shell, version);
                registryRestore(shell, previous);
                return InstallResult.of(rejected(entry, SyncReasons.REGISTRY_REJECTED));
            }
            try {
                ShellFiles.writeCurrent(paths, shell, version);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "current pointer write failed shell={0}", shell);
                registryRestore(shell, previous);
                ShellFiles.deleteVersion(paths, shell, version);
                return InstallResult.of(rejected(entry, SyncReasons.CURRENT_WRITE_FAILED));
            }
            String switchedFrom = currentNow.orElse(previousCurrent);
            if (switchedFrom != null) {
                store.setPrevious(shell, switchedFrom);
            } else {
                store.clearPrevious(shell);
            }
            store.recordInstalled(shell, version, clock.instant());
            if (clearPin || pinNow.isPresent()) {
                store.clearPin(shell);
            }
            try {
                ShellFiles.prune(paths, shell, VersionRetention.keep(
                        version,
                        store.latestSuccessExcept(shell, version),
                        store.previous(shell),
                        store.installed(shell)));
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "shell prune failed shell={0}", shell);
            }
            Set<String> removed = change.map(CredentialDeclarationChange::removed).orElseGet(Set::of);
            deleteRemovedFields(shell, verified.shellDoc(), removed);
            // Tombstone drops only after the new doc is in the registry, so a call cannot observe
            // "enabled" against an empty registry. In-flight requests keep the snapshot they already
            // authorized (audit A01) and are not switched onto this version.
            disabledShells.clear(shell);
            return new InstallResult(outcome(entry, OutcomeKind.INSTALLED, null), change);
        }
    }

    /**
     * Reef left this shell out of the manifest. Record the tombstone before dropping the registry
     * entry so new calls return {@code SHELL_DISABLED} instead of a bare not-found. The authorized
     * snapshot of a request already inside the engine is left alone (audit A01); this path does
     * not install a replacement version.
     */
    private void deactivate(String shell, List<ShellOutcome> shells, List<String> removed) {
        if (!ShellFiles.safeSegment(shell)) {
            disabledShells.mark(shell, null, clock.instant());
            registry.remove(shell);
            removed.add(shell);
            shells.add(new ShellOutcome(shell, null, OutcomeKind.DISABLED, SyncReasons.DISABLED));
            return;
        }
        synchronized (ShellFileLocks.of(shell)) {
            String version = ShellFiles.readCurrent(paths, shell).orElse(null);
            try {
                disabledShells.mark(shell, version, clock.instant());
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "disable tombstone failed shell={0}", shell);
                shells.add(new ShellOutcome(shell, version, OutcomeKind.REJECTED, SyncReasons.DISABLE_FAILED));
                return;
            }
            registry.remove(shell);
            try {
                ShellFiles.clearCurrent(paths, shell);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "clear current failed shell={0}", shell);
            }
            removed.add(shell);
            shells.add(new ShellOutcome(shell, version, OutcomeKind.DISABLED, SyncReasons.DISABLED));
        }
    }

    private InstallResult keepCurrent(ReefClient.CatalogEntry entry) {
        String shell = entry.shell();
        if (!ShellFiles.safeSegment(shell)) {
            return InstallResult.of(rejected(entry, SyncReasons.PATH_UNSAFE));
        }
        synchronized (ShellFileLocks.of(shell)) {
            Optional<String> currentNow = ShellFiles.readCurrent(paths, shell);
            if (currentNow.isEmpty() || !currentNow.get().equals(entry.version())) {
                return null;
            }
            if (registry.find(shell).isEmpty() && !loadInstalled(shell, entry.version())) {
                return InstallResult.of(rejected(entry, SyncReasons.PACKAGE_MISSING));
            }
            disabledShells.clear(shell);
            if (store.pin(shell).isPresent()) {
                store.clearPin(shell);
            }
            return InstallResult.of(outcome(entry, OutcomeKind.SKIPPED_SAME, SyncReasons.SAME_VERSION));
        }
    }

    private boolean loadInstalled(String shell, String version) {
        try {
            java.nio.file.Path zip = ShellFiles.versionDir(paths, shell, version).resolve("package.zip");
            if (!Files.isRegularFile(zip) || Files.isSymbolicLink(zip) || Files.size(zip) > ShellFiles.MAX_ZIP_BYTES) {
                return false;
            }
            VerifiedShell verified = ShellPackage.readVerified(Files.readAllBytes(zip), settings.reefKey());
            if (!shell.equals(verified.manifest().shell()) || !version.equals(verified.manifest().version())) {
                return false;
            }
            registry.put(verified.shellDoc());
            return true;
        } catch (RuntimeException | IOException ex) {
            registry.remove(shell);
            return false;
        }
    }

    private void retryClearDisabledCurrents() {
        for (String shell : disabledShells.names()) {
            if (!ShellFiles.safeSegment(shell)) {
                continue;
            }
            synchronized (ShellFileLocks.of(shell)) {
                if (!disabledShells.contains(shell)) {
                    continue;
                }
                try {
                    ShellFiles.clearCurrent(paths, shell);
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "clear current failed shell={0}", shell);
                }
            }
        }
    }

    private void retryPendingCredentialDeletes() {
        for (String shell : store.shellsWithPendingCredentialDeletes()) {
            deleteRemovedFields(shell, registry.find(shell).orElse(null), Set.of());
        }
    }

    private void deleteRemovedFields(String shell, ShellDoc installed, Set<String> extra) {
        Set<String> fields = new TreeSet<>(store.pendingCredentialFields(shell));
        if (extra != null) {
            fields.addAll(extra);
        }
        if (installed != null && installed.credentials() != null) {
            fields.removeAll(installed.credentials().keySet());
        }
        if (fields.isEmpty()) {
            store.replacePendingCredentialFields(shell, Set.of());
            return;
        }
        try {
            deleteOfficialFields(shell, fields);
            store.replacePendingCredentialFields(shell, Set.of());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "credential delete failed shell={0} count={1}", new Object[] {shell, fields.size()});
            store.replacePendingCredentialFields(shell, fields);
        }
    }

    private void deleteOfficialFields(String shell, Set<String> fields) {
        if (credentialDeleteFailures > 0) {
            credentialDeleteFailures--;
            throw new IllegalStateException("credential delete failed");
        }
        vault.deleteFields(Origin.OFFICIAL, shell, fields);
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

    private ShellDoc previousDoc(String shell) {
        Optional<ShellDoc> live = registry.find(shell);
        if (live.isPresent()) {
            return live.get();
        }
        Optional<String> current = ShellFiles.readCurrent(paths, shell);
        if (current.isEmpty()) {
            return null;
        }
        java.nio.file.Path yaml = ShellFiles.versionDir(paths, shell, current.get()).resolve("shell.yaml");
        if (!Files.isRegularFile(yaml)) {
            return null;
        }
        try {
            return ShellParser.parse(Files.readString(yaml));
        } catch (RuntimeException | IOException ex) {
            return null;
        }
    }

    private void registryRestore(String shell, ShellDoc previous) {
        if (previous == null) {
            registry.remove(shell);
            return;
        }
        try {
            registry.put(previous);
        } catch (RuntimeException ex) {
            registry.remove(shell);
        }
    }

    private String redact(String message) {
        if (message == null || message.isBlank()) {
            return "shell sync failed";
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

    private static String registryReason(ShellRejectedException ex) {
        StringBuilder codes = new StringBuilder(SyncReasons.REGISTRY_REJECTED);
        for (ValidationIssue issue : ex.issues()) {
            codes.append(':').append(issue.code());
        }
        return codes.toString();
    }

    private static ShellOutcome outcome(ReefClient.CatalogEntry entry, OutcomeKind kind, String reason) {
        return new ShellOutcome(entry.shell(), entry.version(), kind, reason);
    }

    private static ShellOutcome rejected(ReefClient.CatalogEntry entry, String reason) {
        return outcome(entry, OutcomeKind.REJECTED, reason);
    }

    private record InstallResult(ShellOutcome outcome, Optional<CredentialDeclarationChange> change) {
        static InstallResult of(ShellOutcome outcome) {
            return new InstallResult(outcome, Optional.empty());
        }
    }
}
