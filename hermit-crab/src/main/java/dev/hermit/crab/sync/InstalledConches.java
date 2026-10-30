package dev.hermit.crab.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Origin;

public final class InstalledConches {

    private static final Logger LOG = Logger.getLogger(InstalledConches.class.getName());

    private InstalledConches() {
    }

    public static LoadReport loadAll(CrabPaths paths, PublicKey key, ConchRegistry registry) {
        return loadAll(paths, key, registry, Set.of());
    }

    /**
     * Conches in {@code disabled} are left unloaded even when {@code current} still points at a
     * package. That keeps a Reef disable in force across restart.
     */
    public static LoadReport loadAll(CrabPaths paths, PublicKey key, ConchRegistry registry, Set<String> disabled) {
        if (paths == null || key == null || registry == null) {
            throw new IllegalArgumentException("paths, key, and registry are required");
        }
        Set<String> skipped = disabled == null ? Set.of() : disabled;
        List<String> loaded = new ArrayList<>();
        List<ConchOutcome> failures = new ArrayList<>();
        try (Stream<Path> children = Files.list(paths.conches())) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String conch = child.getFileName().toString();
                if (!ConchFiles.safeSegment(conch)) {
                    failures.add(new ConchOutcome(conch, null, OutcomeKind.REJECTED, SyncReasons.PATH_UNSAFE));
                    continue;
                }
                if (skipped.contains(conch)) {
                    continue;
                }
                synchronized (ConchFileLocks.of(conch)) {
                    loadOne(paths, key, registry, conch, loaded, failures);
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
            ConchRegistry registry,
            String conch,
            List<String> loaded,
            List<ConchOutcome> failures
    ) {
        Path current = ConchFiles.conchDir(paths, conch).resolve("current");
        if (!Files.isRegularFile(current)) {
            return;
        }
        String version;
        try {
            version = Files.readString(current).trim();
        } catch (IOException ex) {
            failures.add(failure(conch, null, SyncReasons.PACKAGE_MISSING));
            return;
        }
        if (!ConchFiles.safeSegment(version)) {
            failures.add(failure(conch, version, SyncReasons.PATH_UNSAFE));
            return;
        }
        Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
        if (!Files.isRegularFile(zip)) {
            failures.add(failure(conch, version, SyncReasons.PACKAGE_MISSING));
            return;
        }
        try {
            if (Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                failures.add(failure(conch, version, SyncReasons.ZIP_TOO_LARGE));
                return;
            }
            VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), key);
            if (!conch.equals(verified.manifest().conch()) || !version.equals(verified.manifest().version())) {
                failures.add(failure(conch, version, SyncReasons.MANIFEST_ENTRY_MISMATCH));
                return;
            }
            if (registry.origin(conch).orElse(null) == Origin.LOCAL) {
                failures.add(failure(conch, version, SyncReasons.LOCAL_KEPT));
                LOG.log(Level.WARNING, "skip installed conch {0} because a local conch uses that name", conch);
                return;
            }
            registry.put(verified.conchDoc(), Origin.OFFICIAL);
            loaded.add(conch);
        } catch (PackageVerificationException ex) {
            failures.add(failure(conch, version, ex.reason().name()));
            LOG.log(Level.WARNING, "skip installed conch {0} version {1} reason {2}",
                    new Object[] {conch, version, ex.reason().name()});
        } catch (ConchRejectedException ex) {
            String reason = SyncReasons.REGISTRY_REJECTED;
            failures.add(failure(conch, version, reason));
            LOG.log(Level.WARNING, "skip installed conch {0} version {1} reason {2}",
                    new Object[] {conch, version, reason});
        } catch (IOException ex) {
            failures.add(failure(conch, version, SyncReasons.PACKAGE_MISSING));
        }
    }

    private static ConchOutcome failure(String conch, String version, String reason) {
        return new ConchOutcome(conch, version, OutcomeKind.REJECTED, reason);
    }
}
