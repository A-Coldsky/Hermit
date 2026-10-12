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
import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.common.pkg.VerifiedShell;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.report.ReportQueue;
import dev.hermit.crab.store.CrabPaths;

public final class RollbackGuard {

    public static final int MISMATCH_LIMIT = 5;
    public static final String REASON = "RULE_MISMATCH";

    private static final Logger LOG = Logger.getLogger(RollbackGuard.class.getName());

    private final CrabPaths paths;
    private final PublicKey reefKey;
    private final ShellRegistry registry;
    private final SyncStore store;
    private final ReportQueue reports;
    private final Clock clock;

    public RollbackGuard(
            CrabPaths paths,
            PublicKey reefKey,
            ShellRegistry registry,
            DataSource dataSource,
            ReportQueue reports
    ) {
        this(paths, reefKey, registry, dataSource, reports, Clock.systemUTC());
    }

    public RollbackGuard(
            CrabPaths paths,
            PublicKey reefKey,
            ShellRegistry registry,
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

    public void onResult(String shell, String version, ExecutionResult result) {
        if (shell == null || version == null || result == null) {
            throw new IllegalArgumentException("shell, version, and result are required");
        }
        if (!ShellFiles.safeSegment(shell) || !ShellFiles.safeSegment(version)) {
            return;
        }
        String rolledBackTo = null;
        synchronized (ShellFileLocks.of(shell)) {
            if (store.pin(shell).filter(pin -> version.equals(pin.badVersion())).isPresent()) {
                if (result.success()) {
                    store.recordSuccess(shell, version, clock.instant());
                    store.clearStreak(shell);
                }
                return;
            }
            if (result.success()) {
                store.recordSuccess(shell, version, clock.instant());
                store.clearStreak(shell);
                return;
            }
            if (result.error() == null || result.error().code() != ErrorCode.RULE_MISMATCH) {
                return;
            }
            int streak = store.recordMismatch(shell, version);
            if (streak >= MISMATCH_LIMIT) {
                rolledBackTo = rollback(shell, version);
            }
        }
        if (rolledBackTo != null) {
            try {
                reports.enqueueRollback(shell, version, rolledBackTo, REASON, clock.instant());
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "rollback report failed shell={0}", shell);
            }
        }
    }

    private String rollback(String shell, String version) {
        Optional<String> previous = store.previous(shell);
        if (previous.isEmpty() || !store.succeeded(shell, previous.get())) {
            LOG.log(Level.WARNING, "rollback skipped shell={0} reason=previous_not_successful", shell);
            return null;
        }
        String target = previous.get();
        if (!ShellFiles.safeSegment(target)) {
            return null;
        }
        java.nio.file.Path zip = ShellFiles.versionDir(paths, shell, target).resolve("package.zip");
        if (!Files.isRegularFile(zip)) {
            LOG.log(Level.WARNING, "rollback skipped shell={0} reason=package_missing", shell);
            return null;
        }
        try {
            if (Files.size(zip) > ShellFiles.MAX_ZIP_BYTES) {
                LOG.log(Level.WARNING, "rollback skipped shell={0} reason=package_too_large", shell);
                return null;
            }
            VerifiedShell verified = ShellPackage.readVerified(Files.readAllBytes(zip), reefKey);
            if (!shell.equals(verified.manifest().shell()) || !target.equals(verified.manifest().version())) {
                LOG.log(Level.WARNING, "rollback skipped shell={0} reason=manifest_mismatch", shell);
                return null;
            }
            registry.put(verified.shellDoc());
            ShellFiles.writeCurrent(paths, shell, target);
        } catch (PackageVerificationException ex) {
            LOG.log(Level.WARNING, "rollback skipped shell={0} reason={1}",
                    new Object[] {shell, ex.reason().name()});
            return null;
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "rollback skipped shell={0} reason=unreadable", shell);
            return null;
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "rollback skipped shell={0} reason=registry_rejected", shell);
            return null;
        }
        store.pin(shell, version, target, clock.instant());
        store.setPrevious(shell, version);
        store.clearStreak(shell);
        return target;
    }
}
