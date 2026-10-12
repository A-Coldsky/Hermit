package dev.hermit.reef.security;

import dev.hermit.reef.config.ReefProperties;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class JwtSecretStore {

    static final int SECRET_LENGTH = 32;
    static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final byte[] secret;

    public JwtSecretStore(ReefProperties properties) {
        this.secret = loadOrCreate(properties.dataDirectory().resolve("keys").resolve("jwt.secret")).clone();
    }

    public byte[] secret() {
        return secret.clone();
    }

    static byte[] loadOrCreate(Path file) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                restrict(file);
                return readRequired(file);
            }
            byte[] generated = new byte[SECRET_LENGTH];
            new SecureRandom().nextBytes(generated);
            Path temporary = Files.createTempFile(
                    file.getParent(),
                    "jwt-",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
            try {
                Files.write(temporary, generated);
                restrict(temporary);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException ex) {
                    return readRequired(file);
                }
                restrict(file);
                return generated.clone();
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not initialize jwt.secret", ex);
        }
    }

    private static byte[] readRequired(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != SECRET_LENGTH) {
            throw new IllegalStateException("jwt.secret must contain exactly 32 bytes");
        }
        return bytes;
    }

    private static void restrict(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new IOException("POSIX permissions are required for jwt.secret");
        }
        Files.setPosixFilePermissions(file, OWNER_READ_WRITE);
    }
}
