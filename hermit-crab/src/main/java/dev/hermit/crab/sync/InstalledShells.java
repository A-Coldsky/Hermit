package dev.hermit.crab.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.common.pkg.VerifiedShell;
import dev.hermit.crab.engine.ShellRegistry;
import dev.hermit.crab.engine.ShellRejectedException;
import dev.hermit.crab.store.CrabPaths;

public final class InstalledShells {

    private static final Logger LOG = Logger.getLogger(InstalledShells.class.getName());

    private InstalledShells() {
    }

    public static LoadReport loadAll(CrabPaths paths, PublicKey key, ShellRegistry registry) {
        if (paths == null || key == null || registry == null) {
            throw new IllegalArgumentException("paths, key, and registry are required");
        }
        List<String> loaded = new ArrayList<>();
        List<ShellOutcome> failures = new ArrayList<>();
        try (Stream<Path> children = Files.list(paths.shells())) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String shell = child.getFileName().toString();
                if (!ShellFiles.safeSegment(shell)) {
                    failures.add(new ShellOutcome(shell, null, OutcomeKind.REJECTED, SyncReasons.PATH_UNSAFE));
                    continue;
                }
                synchronized (ShellFileLocks.of(shell)) {
                    loadOne(paths, key, registry, shell, loaded, failures);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new LoadReport(loaded, failures);
    }

    private static void loadOne(
            CrabPaths paths,
            PublicKey key,
            ShellRegistry registry,
            String shell,
            List<String> loaded,
            List<ShellOutcome> failures
    ) {
        Path current = ShellFiles.shellDir(paths, shell).resolve("current");
        if (!Files.isRegularFile(current)) {
            return;
        }
        String version;
        try {
            version = Files.readString(current).trim();
        } catch (IOException ex) {
            failures.add(failure(shell, null, SyncReasons.PACKAGE_MISSING));
            return;
        }
        if (!ShellFiles.safeSegment(version)) {
            failures.add(failure(shell, version, SyncReasons.PATH_UNSAFE));
            return;
        }
        Path zip = ShellFiles.versionDir(paths, shell, version).resolve("package.zip");
        if (!Files.isRegularFile(zip)) {
            failures.add(failure(shell, version, SyncReasons.PACKAGE_MISSING));
            return;
        }
        try {
            if (Files.size(zip) > ShellFiles.MAX_ZIP_BYTES) {
                failures.add(failure(shell, version, SyncReasons.ZIP_TOO_LARGE));
                return;
            }
            VerifiedShell verified = ShellPackage.readVerified(Files.readAllBytes(zip), key);
            if (!shell.equals(verified.manifest().shell()) || !version.equals(verified.manifest().version())) {
                failures.add(failure(shell, version, SyncReasons.MANIFEST_ENTRY_MISMATCH));
                return;
            }
            registry.put(verified.shellDoc());
            loaded.add(shell);
        } catch (PackageVerificationException ex) {
            failures.add(failure(shell, version, ex.reason().name()));
            LOG.log(Level.WARNING, "skip installed shell {0} version {1} reason {2}",
                    new Object[] {shell, version, ex.reason().name()});
        } catch (ShellRejectedException ex) {
            String reason = SyncReasons.REGISTRY_REJECTED;
            failures.add(failure(shell, version, reason));
            LOG.log(Level.WARNING, "skip installed shell {0} version {1} reason {2}",
                    new Object[] {shell, version, reason});
        } catch (IOException ex) {
            failures.add(failure(shell, version, SyncReasons.PACKAGE_MISSING));
        }
    }

    private static ShellOutcome failure(String shell, String version, String reason) {
        return new ShellOutcome(shell, version, OutcomeKind.REJECTED, reason);
    }
}
