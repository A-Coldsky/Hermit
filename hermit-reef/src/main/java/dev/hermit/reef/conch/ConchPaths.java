package dev.hermit.reef.conch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConchPaths {

    public static final int MAX_UPLOAD_BYTES = 1024 * 1024;

    private ConchPaths() {
    }

    public static Path conchesRoot(Path dataDir) throws IOException {
        Path root = dataDir.toAbsolutePath().normalize().resolve("conches");
        Files.createDirectories(root);
        return root.toRealPath();
    }

    /**
     * 版本目录必须落在 {@code data-dir/conches} 的真实路径下。
     * {@code conch}、{@code version} 只能各占一个路径段，不能是符号链接。
     */
    public static Path versionDirectory(Path dataDir, String conch, String version, boolean create) throws IOException {
        if (!singleSegment(conch) || !singleSegment(version)) {
            throw new ConchPathException();
        }
        Path root = conchesRoot(dataDir);
        Path conchDir = root.resolve(conch);
        if (Files.isSymbolicLink(conchDir)) {
            throw new ConchPathException();
        }
        Path versionDir = conchDir.resolve(version).normalize();
        if (!versionDir.startsWith(root) || Files.isSymbolicLink(versionDir)) {
            throw new ConchPathException();
        }
        if (create) {
            Files.createDirectories(versionDir);
        } else if (!Files.isDirectory(versionDir)) {
            throw new ConchPathException();
        }
        Path real = versionDir.toRealPath();
        if (!real.startsWith(root)) {
            throw new ConchPathException();
        }
        return real;
    }

    public static boolean singleSegment(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
