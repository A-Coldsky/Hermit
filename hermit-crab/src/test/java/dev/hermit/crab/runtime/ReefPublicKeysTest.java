package dev.hermit.crab.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

import dev.hermit.common.pkg.Ed25519Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReefPublicKeysTest {
    @TempDir
    Path temp;

    @Test
    void readsPemTextOrAPathAndOmitsKeyMaterialFromErrors() throws Exception {
        KeyPair keys = Ed25519Keys.generate();
        String pem = Ed25519Keys.writePublicPem(keys.getPublic());
        assertThat(ReefPublicKeys.read(pem, temp)).isEqualTo(keys.getPublic());
        assertThat(ReefPublicKeys.read(null, temp)).isNull();
        assertThat(ReefPublicKeys.read("  ", temp)).isNull();

        Path file = temp.resolve("signing.pub");
        Files.writeString(file, pem);
        assertThat(ReefPublicKeys.read(file.toString(), temp)).isEqualTo(keys.getPublic());
        assertThat(ReefPublicKeys.read("signing.pub", temp)).isEqualTo(keys.getPublic());

        assertThatThrownBy(() -> ReefPublicKeys.read(pem.replace("PUBLIC KEY", "PRIVATE KEY"), temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reef.public_key is not a usable Ed25519 public key")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(pem));
        assertThatThrownBy(() -> ReefPublicKeys.read("missing.pub", temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reef.public_key file cannot be read");
    }
}
