package dev.hermit.reef.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ShellPaths {

    public static final int MAX_UPLOAD_BYTES = 1024 * 1024;

    private ShellPaths() {
    }

    public static Path shellsRoot(Path dataDir) throws IOException {
        Path root = dataDir.toAbsolutePath().normalize().resolve("shells");
        Files.createDirectories(root);
        return root.toRealPath();
    }

    /**
     * 版本目录必须落在 {@code data-dir/shells} 的真实路径下。
     * {@code shell}、{@code version} 只能各占一个路径段，不能是符号链接。
     */
    public static Path versionDirectory(Path dataDir, String shell, String version, boolean create) throws IOException {
        if (!singleSegment(shell) || !singleSegment(version)) {
            throw new ShellPathException();
        }
        Path root = shellsRoot(dataDir);
        Path shellDir = root.resolve(shell);
        if (Files.isSymbolicLink(shellDir)) {
            throw new ShellPathException();
        }
        Path versionDir = shellDir.resolve(version).normalize();
        if (!versionDir.startsWith(root) || Files.isSymbolicLink(versionDir)) {
            throw new ShellPathException();
        }
        if (create) {
            Files.createDirectories(versionDir);
        } else if (!Files.isDirectory(versionDir)) {
            throw new ShellPathException();
        }
        Path real = versionDir.toRealPath();
        if (!real.startsWith(root)) {
            throw new ShellPathException();
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
