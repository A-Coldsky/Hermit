package dev.hermit.reef.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtSecretStoreTest {

    @TempDir
    Path directory;

    @Test
    void createsOwnerOnlySecretAndReusesIt() throws Exception {
        Path file = directory.resolve("jwt.secret");

        byte[] first = JwtSecretStore.loadOrCreate(file);
        byte[] second = JwtSecretStore.loadOrCreate(file);

        assertThat(first).hasSize(JwtSecretStore.SECRET_LENGTH);
        assertThat(second).containsExactly(first);
        assertThat(Files.readAllBytes(file)).containsExactly(first);
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void rejectsSecretWithWrongLengthWithoutEchoingContents() throws Exception {
        Path file = directory.resolve("jwt.secret");
        Files.writeString(file, "short-secret");

        assertThatThrownBy(() -> JwtSecretStore.loadOrCreate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jwt.secret must contain exactly 32 bytes")
                .hasMessageNotContaining("short-secret");
    }
}
