package dev.hermit.crab.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Origin;

public final class InstalledConches {

    public record VerifiedYaml(String conch, String version, byte[] bytes) {
        public VerifiedYaml {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static final Logger LOG = Logger.getLogger(InstalledConches.class.getName());

    private InstalledConches() {
    }

    /**
     * Loads every package that still has a {@code current} pointer, including one Reef has
     * disabled. A tombstone does not unload the package. A package whose {@code current} was
     * already deleted (an older disable) stays unloaded.
     */
    public static LoadReport loadAll(CrabPaths paths, PublicKey key, ConchRegistry registry) {
        if (paths == null || key == null || registry == null) {
            throw new IllegalArgumentException("paths, key, and registry are required");
        }
        List<String> loaded = new ArrayList<>();
        List<ConchOutcome> failures = new ArrayList<>();
        try (Stream<Path> children = Files.list(paths.conches())) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String conch = child.getFileName().toString();
                if (!ConchFiles.safeSegment(conch)) {
                    failures.add(new ConchOutcome(conch, null, OutcomeKind.REJECTED, SyncReasons.PATH_UNSAFE));
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
            if (registry.origin(conch) == Origin.LOCAL) {
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
            String yaml = "";
            try {
                VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), key);
                yaml = new String(verified.conchYaml(), StandardCharsets.UTF_8);
            } catch (RuntimeException | IOException ignored) {
                yaml = "";
            }
            List<ValidationIssue> located = ImportIssueLines.locate(yaml, ex.issues());
            LOG.warning("skip installed conch " + conch + " version " + version
                    + " reason " + reason
                    + " codes=" + RuleDisabled.codes(located)
                    + " lines=" + RuleDisabled.lines(located));
        } catch (IOException ex) {
            failures.add(failure(conch, version, SyncReasons.PACKAGE_MISSING));
        }
    }

    /**
     * 只读读取本机已安装的 Reef 正式规则包原始 YAML。读取前按 Reef 公钥重新验签并核对清单，
     * 绝不修改文件，也不返回未通过验签的内容。
     */
    public static Optional<VerifiedYaml> readVerifiedYaml(CrabPaths paths, PublicKey key, String conch) {
        if (paths == null || key == null || !ConchFiles.safeSegment(conch)) {
            return Optional.empty();
        }
        synchronized (ConchFileLocks.of(conch)) {
            Path current = ConchFiles.conchDir(paths, conch).resolve("current");
            if (Files.isSymbolicLink(current) || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String version;
            try {
                version = Files.readString(current, StandardCharsets.UTF_8).trim();
            } catch (IOException ex) {
                return Optional.empty();
            }
            if (!ConchFiles.safeSegment(version)) {
                return Optional.empty();
            }
            Path zip = ConchFiles.versionDir(paths, conch, version).resolve("package.zip");
            if (Files.isSymbolicLink(zip) || !Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try {
                if (Files.size(zip) > ConchFiles.MAX_ZIP_BYTES) {
                    return Optional.empty();
                }
                VerifiedConch verified = ConchPackage.readVerified(Files.readAllBytes(zip), key);
                if (!conch.equals(verified.manifest().conch()) || !version.equals(verified.manifest().version())) {
                    return Optional.empty();
                }
                return Optional.of(new VerifiedYaml(conch, version, verified.conchYaml()));
            } catch (PackageVerificationException | IOException ex) {
                return Optional.empty();
            }
        }
    }

    private static ConchOutcome failure(String conch, String version, String reason) {
        return new ConchOutcome(conch, version, OutcomeKind.REJECTED, reason);
    }
}
