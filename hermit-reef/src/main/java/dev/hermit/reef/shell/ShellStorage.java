package dev.hermit.reef.shell;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

@Component
class ShellStorage {

    private final Path dataDir;

    ShellStorage(ReefProperties properties) {
        this.dataDir = properties.dataDirectory();
    }

    void writeYaml(String shell, String version, byte[] yaml) {
        write(shell, version, "shell.yaml", yaml);
    }

    void writeZip(String shell, String version, byte[] zip) {
        write(shell, version, "package.zip", zip);
    }

    byte[] readYaml(String shell, String version) {
        return read(shell, version, "shell.yaml");
    }

    byte[] readZip(String shell, String version) {
        return read(shell, version, "package.zip");
    }

    void deleteZip(String shell, String version) {
        delete(shell, version, "package.zip");
    }

    void deleteYaml(String shell, String version) {
        delete(shell, version, "shell.yaml");
    }

    private void write(String shell, String version, String fileName, byte[] bytes) {
        Path dir = versionDir(shell, version, true);
        Path target = dir.resolve(fileName);
        Path temp = null;
        boolean moved = false;
        try {
            temp = Files.createTempFile(dir, ".part-", ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } catch (IOException ex) {
            throw internal();
        } finally {
            if (!moved && temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ex) {
                    // 临时文件清不掉不影响已经失败的写入。
                }
            }
        }
    }

    private byte[] read(String shell, String version, String fileName) {
        Path file = versionDir(shell, version, false).resolve(fileName);
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new ShellPathException();
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw internal();
        }
    }

    private void delete(String shell, String version, String fileName) {
        try {
            Path dir = ShellPaths.versionDirectory(dataDir, shell, version, false);
            Path file = dir.resolve(fileName);
            if (Files.isSymbolicLink(file)) {
                throw new ShellPathException();
            }
            Files.deleteIfExists(file);
        } catch (ShellPathException ex) {
            return;
        } catch (IOException ex) {
            throw internal();
        }
    }

    private Path versionDir(String shell, String version, boolean create) {
        try {
            return ShellPaths.versionDirectory(dataDir, shell, version, create);
        } catch (IOException ex) {
            throw internal();
        }
    }

    private static ApiException internal() {
        return new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
    }
}
