package dev.hermit.crab.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.store.CrabPaths;

public final class RollbackGuard {

    public static final int MISMATCH_LIMIT = 5;
    public static final String REASON = "RULE_MISMATCH";

    private static final Logger LOG = Logger.getLogger(RollbackGuard.class.getName());

    private final CrabPaths paths;
    private final PublicKey reefKey;
    private final ConchRegistry registry;
    private final SyncStore store;
    private final ReportQueue reports;
    private final Clock clock;

    public RollbackGuard(
            CrabPaths paths,
            PublicKey reefKey,
            ConchRegistry registry,
            DataSource dataSource,
            ReportQueue reports
    ) {
        this(paths, reefKey, registry, dataSource, reports, Clock.systemUTC());
    }

    public RollbackGuard(
            CrabPaths paths,
            PublicKey reefKey,
            ConchRegistry registry,
            DataSource dataSource,
            ReportQueue reports,
            Clock clock
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.reefKey = Objects.requireNonNull(reefKey, "reefKey");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = new SyncStore(Objects.requireNonNull(dataSource, "dataSource"));
        this.reports = Objects.requireNonNull(reports, "reports");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void onResult(String conch, String version, ExecutionResult result) {
        if (conch == null || version == null || result == null) {
            throw new IllegalArgumentException("conch, version, and result are required");
        }
        if (!ConchFiles.safeSegment(conch) || !ConchFiles.safeSegment(version)) {
            return;
        }
        String rolledBackTo = null;
        synchronized (ConchFileLocks.of(conch)) {
            if (store.pin(conch).filter(pin -> version.equals(pin.badVersion())).isPresent()) {
                if (result.success()) {
                    store.recordSuccess(conch, version, clock.instant());
                    store.clearStreak(conch);
                }
                return;
            }
            if (result.success()) {
                store.recordSuccess(conch, version, clock.instant());
                store.clearStreak(conch);
                return;
            }
            if (result.error() == null || result.error().code() != ErrorCode.RULE_MISMATCH) {
                return;
            }
            int streak = store.recordMismatch(conch, version);
            if (streak >= MISMATCH_LIMIT) {
                rolledBackTo = rollback(conch, version);
            }
        }
        if (rolledBackTo != null) {
            try {
                reports.enqueueRollback(conch, version, rolledBackTo, REASON, clock.instant());
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "rollback report failed conch={0}", conch);
            }
        }
    }

    private String rollback(String conch, String version) {
        Optional<String> previous = store.previous(conch);
        if (previous.isEmpty() || !store.succeeded(conch, previous.get())) {
            LOG.log(Level.WARNING, "rollback skipped conch={0} reason=previous_not_successful", conch);
            return null;
        }
        String target = previous.get();
        if (!ConchFiles.safeSegment(target)) {
            return null;
        }
        java.nio.file.Path zip = ConchFiles.versionDir(paths, conch, target).resolve("package.zip");
        if (!Files.isRegularFile(zip)) {
            LOG.log(Level.WARNING, "rollback skipped conch={0} reason=package_missing", conch);
            return null;
        }
        try {
            if (Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                LOG.log(Level.WARNING, "rollback skipped conch={0} reason=package_too_large", conch);
                return null;
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), reefKey);
            if (!conch.equals(verified.manifest().conch()) || !target.equals(verified.manifest().version())) {
                LOG.log(Level.WARNING, "rollback skipped conch={0} reason=manifest_mismatch", conch);
                return null;
            }
            registry.put(verified.conchDoc());
            ConchFiles.writeCurrent(paths, conch, target);
        } catch (PackageVerificationException ex) {
            LOG.log(Level.WARNING, "rollback skipped conch={0} reason={1}",
                    new Object[] {conch, ex.reason().name()});
            return null;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "rollback skipped conch={0} reason=unreadable", conch);
            return null;
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "rollback skipped conch={0} reason=registry_rejected", conch);
            return null;
        }
        store.pin(conch, version, target, clock.instant());
        store.setPrevious(conch, version);
        store.clearStreak(conch);
        return target;
    }
}
