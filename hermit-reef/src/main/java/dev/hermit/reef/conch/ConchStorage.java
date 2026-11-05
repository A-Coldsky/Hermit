package dev.hermit.reef.conch;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

@Component
class ConchStorage {

    private final Path dataDir;

    ConchStorage(ReefProperties properties) {
        this.dataDir = properties.dataDirectory();
    }

    void writeYaml(String conch, String version, byte[] yaml) {
        write(conch, version, "conch.yaml", yaml);
    }

    void writeZip(String conch, String version, byte[] zip) {
        write(conch, version, "package.zip", zip);
    }

    byte[] readYaml(String conch, String version) {
        return read(conch, version, "conch.yaml");
    }

    byte[] readZip(String conch, String version) {
        return read(conch, version, "package.zip");
    }

    void deleteZip(String conch, String version) {
        delete(conch, version, "package.zip");
    }

    void deleteYaml(String conch, String version) {
        delete(conch, version, "conch.yaml");
    }

    /**
     * 删掉某个尚未发布版本目录里的 YAML，以及发布未提交时可能留下的 ZIP。
     * 不进入其它版本目录。调用方必须已经确认该版本是 {@code UPLOADED} 并删掉了版本行。
     */
    void deleteUploadedFiles(String conch, String version) {
        deleteYaml(conch, version);
        deleteZip(conch, version);
    }

    private void write(String conch, String version, String fileName, byte[] bytes) {
        Path dir = versionDir(conch, version, true);
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

    private byte[] read(String conch, String version, String fileName) {
        Path file = versionDir(conch, version, false).resolve(fileName);
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new ConchPathException();
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw internal();
        }
    }

    private void delete(String conch, String version, String fileName) {
        try {
            Path dir = ConchPaths.versionDirectory(dataDir, conch, version, false);
            Path file = dir.resolve(fileName);
            if (Files.isSymbolicLink(file)) {
                throw new ConchPathException();
            }
            Files.deleteIfExists(file);
        } catch (ConchPathException ex) {
            return;
        } catch (IOException ex) {
            throw internal();
        }
    }

    private Path versionDir(String conch, String version, boolean create) {
        try {
            return ConchPaths.versionDirectory(dataDir, conch, version, create);
        } catch (IOException ex) {
            throw internal();
        }
    }

    private static ApiException internal() {
        return new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
    }
}
