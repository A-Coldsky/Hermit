package dev.hermit.reef.conch;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * 规则审批单 YAML 的独立落盘目录：{@code data-dir/submissions/<id>/conch.yaml}。
 * 与正式发布的 {@code data-dir/conches/} 隔离，避免 MySQL TEXT 64 KiB 限制，也避免待审文件污染正式目录。
 */
@Component
class SubmissionStorage {

    private static final String SUBMISSIONS_DIR = "submissions";
    private static final String YAML_FILE = "conch.yaml";

    private final Path dataDir;

    SubmissionStorage(ReefProperties properties) {
        this.dataDir = properties.dataDirectory();
    }

    String writeYaml(long id, byte[] yaml) {
        Path dir = submissionDir(id, true);
        Path target = dir.resolve(YAML_FILE);
        Path temp = null;
        boolean moved = false;
        try {
            temp = Files.createTempFile(dir, ".part-", ".tmp");
            Files.write(temp, yaml);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            return relativePath(id);
        } catch (IOException ex) {
            throw internal();
        } finally {
            if (!moved && temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    byte[] readYaml(long id) {
        Path file = submissionDir(id, false).resolve(YAML_FILE);
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new ConchPathException();
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw internal();
        }
    }

    void deleteYaml(long id) {
        try {
            Path dir = submissionDir(id, false);
            Path file = dir.resolve(YAML_FILE);
            if (Files.isSymbolicLink(file)) {
                throw new ConchPathException();
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        } catch (ConchPathException ex) {
            return;
        } catch (IOException ignored) {
        }
    }

    static String relativePath(long id) {
        return SUBMISSIONS_DIR + "/" + id + "/" + YAML_FILE;
    }

    private Path submissionDir(long id, boolean create) {
        if (id <= 0) {
            throw new ConchPathException();
        }
        try {
            Path root = dataDir.toAbsolutePath().normalize().resolve(SUBMISSIONS_DIR);
            if (Files.isSymbolicLink(root)) {
                throw new ConchPathException();
            }
            if (create) {
                Files.createDirectories(root);
            } else if (!Files.isDirectory(root)) {
                throw new ConchPathException();
            }
            Path realRoot = root.toRealPath();
            Path dir = realRoot.resolve(Long.toString(id)).normalize();
            if (!dir.startsWith(realRoot) || Files.isSymbolicLink(dir)) {
                throw new ConchPathException();
            }
            if (create) {
                Files.createDirectories(dir);
            } else if (!Files.isDirectory(dir)) {
                throw new ConchPathException();
            }
            Path realDir = dir.toRealPath();
            if (!realRoot.equals(realDir.getParent())) {
                throw new ConchPathException();
            }
            return realDir;
        } catch (ConchPathException ex) {
            throw ex;
        } catch (IOException ex) {
            throw internal();
        }
    }

    private static ApiException internal() {
        return new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
    }
}
