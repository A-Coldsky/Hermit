package dev.hermit.crab.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import dev.hermit.crab.store.CrabPaths;

final class ConchFiles {

    static final int MAX_ZIP_BYTES = 1024 * 1024;

    private ConchFiles() {
    }

    static boolean safeSegment(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return false;
        }
        if (".".equals(value) || "..".equals(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    static Path conchDir(CrabPaths paths, String conch) {
        Path dir = paths.conches().resolve(conch).normalize();
        if (!dir.startsWith(paths.conches())) {
            throw new IllegalArgumentException("conch path escapes the conches directory");
        }
        return dir;
    }

    static Path versionDir(CrabPaths paths, String conch, String version) {
        Path dir = conchDir(paths, conch).resolve(version).normalize();
        if (!dir.startsWith(conchDir(paths, conch))) {
            throw new IllegalArgumentException("version path escapes the conch directory");
        }
        return dir;
    }

    static Optional<String> readCurrent(CrabPaths paths, String conch) {
        if (!safeSegment(conch)) {
            return Optional.empty();
        }
        Path current = conchDir(paths, conch).resolve("current");
        if (!Files.isRegularFile(current) || Files.isSymbolicLink(current)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(current, StandardCharsets.UTF_8).trim();
            if (!safeSegment(text)) {
                return Optional.empty();
            }
            return Optional.of(text);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void clearCurrent(CrabPaths paths, String conch) {
        if (!safeSegment(conch)) {
            throw new IllegalArgumentException("conch");
        }
        Path current = conchDir(paths, conch).resolve("current");
        if (Files.isSymbolicLink(current)) {
            throw new IllegalStateException("current is a symlink");
        }
        requireNoSymlink(paths, current);
        try {
            Files.deleteIfExists(current);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void writeCurrent(CrabPaths paths, String conch, String version) {
        Path dir = conchDir(paths, conch);
        Path current = dir.resolve("current");
        requireNoSymlink(paths, current);
        try {
            Files.createDirectories(dir);
            atomicWrite(paths, current, (version + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void install(CrabPaths paths, String conch, String version, byte[] zip, byte[] yaml) {
        Path dir = versionDir(paths, conch, version);
        requireNoSymlink(paths, dir);
        try {
            Files.createDirectories(dir);
            atomicWrite(paths, dir.resolve("package.zip"), zip);
            atomicWrite(paths, dir.resolve("conch.yaml"), yaml);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void deleteVersion(CrabPaths paths, String conch, String version) {
        Path dir = versionDir(paths, conch, version);
        requireNoSymlink(paths, dir);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteTree(dir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void prune(CrabPaths paths, String conch, Set<String> keep) {
        Path dir = conchDir(paths, conch);
        requireNoSymlink(paths, dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (!keep.contains(name)) {
                    deleteTree(child);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Reads a package zip that is a regular file of at most {@link #MAX_ZIP_BYTES}.
     * A symlink anywhere from the conches root to the file fails before the bytes are read.
     */
    static byte[] readZip(CrabPaths paths, Path zip) throws IOException {
        requireNoSymlink(paths, zip);
        if (Files.isSymbolicLink(zip) || !Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("package zip is not a regular file");
        }
        if (Files.size(zip) > MAX_ZIP_BYTES) {
            throw new IOException("package zip exceeds 1MB");
        }
        return Files.readAllBytes(zip);
    }

    /**
     * Segments that already exist between the conches root and {@code target} must not be symlinks.
     * The walk does not resolve links. {@code ..} and absolute paths are still rejected lexically.
     */
    private static void requireNoSymlink(CrabPaths paths, Path target) {
        Path root = paths.conches().toAbsolutePath().normalize();
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("conch path escapes the conches directory");
        }
        Path cursor = root;
        for (Path segment : root.relativize(absolute)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalStateException("conch path contains a symlink");
            }
        }
    }

    private static void atomicWrite(CrabPaths paths, Path target, byte[] bytes) throws IOException {
        requireNoSymlink(paths, target);
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("conch path escapes the conches directory");
        }
        Path tmp = Files.createTempFile(parent, target.getFileName().toString() + "-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            confirmInside(paths.conches(), target);
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }

    private static void confirmInside(Path root, Path written) throws IOException {
        Path realRoot = root.toRealPath();
        Path realWritten = written.toRealPath();
        if (realWritten.startsWith(realRoot)) {
            return;
        }
        Files.deleteIfExists(written);
        throw new IOException("conch path escapes the conches directory");
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
