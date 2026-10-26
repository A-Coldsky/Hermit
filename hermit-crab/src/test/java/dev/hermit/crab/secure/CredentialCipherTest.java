package dev.hermit.crab.secure;

import dev.hermit.crab.store.Origin;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {
    private static final String PLAINTEXT = "HermitCipherPlaintext-7f3c";

    private final CredentialCipher cipher = new CredentialCipher(testKey());

    @Test
    void roundTripsAndUsesAFreshNonce() {
        byte[] first = cipher.encrypt(Origin.OFFICIAL, "oa", "alice", "password", PLAINTEXT);
        byte[] second = cipher.encrypt(Origin.OFFICIAL, "oa", "alice", "password", PLAINTEXT);
        assertThat(first).isNotEqualTo(second);
        assertThat(first[0]).isEqualTo((byte) 0x01);
        assertThat(first).hasSize(1 + 12 + PLAINTEXT.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(cipher.decrypt(Origin.OFFICIAL, "oa", "alice", "password", first)).isEqualTo(PLAINTEXT);
        assertThat(SecretChecks.indexOf(first, PLAINTEXT.getBytes(StandardCharsets.UTF_8))).isNegative();
        SecretChecks.absent(cipher.toString(), PLAINTEXT);
    }

    @Test
    void unicodeRoundTripUsesUtf8Length() {
        String conch = "壳";
        String plaintext = "密码值-secret";
        byte[] stored = cipher.encrypt(Origin.OFFICIAL, conch, "alice", "password", plaintext);
        assertThat(stored).hasSize(1 + 12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(cipher.decrypt(Origin.OFFICIAL, conch, "alice", "password", stored)).isEqualTo(plaintext);
    }

    @Test
    void tuplesThatDifferOnlyBySplitPositionCannotDecryptEachOther() {
        String account = "acct";
        byte[] conchThenField = cipher.encrypt(Origin.OFFICIAL, "a", account, "b|c", PLAINTEXT);
        byte[] fieldThenConch = cipher.encrypt(Origin.OFFICIAL, "a|b", account, "c", PLAINTEXT);
        assertThat(cipher.decrypt(Origin.OFFICIAL, "a", account, "b|c", conchThenField)).isEqualTo(PLAINTEXT);
        assertThat(cipher.decrypt(Origin.OFFICIAL, "a|b", account, "c", fieldThenConch)).isEqualTo(PLAINTEXT);
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "a|b", account, "c", conchThenField));
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "a", account, "b|c", fieldThenConch));
    }

    @Test
    void movedToAnotherAccountFieldConchOrOriginFails() {
        byte[] stored = cipher.encrypt(Origin.OFFICIAL, "oa", "alice", "password", PLAINTEXT);
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "oa", "bob", "password", stored));
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "oa", "alice", "token", stored));
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "erp", "alice", "password", stored));
        assertRejected(() -> cipher.decrypt(Origin.LOCAL, "oa", "alice", "password", stored));
    }

    @Test
    void flippedByteOrBadVersionFailsWithoutEchoingPlaintext() {
        byte[] stored = cipher.encrypt(Origin.OFFICIAL, "oa", "alice", "password", PLAINTEXT);
        byte[] flipped = Arrays.copyOf(stored, stored.length);
        flipped[flipped.length - 1] ^= 0x01;
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "oa", "alice", "password", flipped));
        byte[] badVersion = Arrays.copyOf(stored, stored.length);
        badVersion[0] = 0x02;
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "oa", "alice", "password", badVersion));
        assertRejected(() -> cipher.decrypt(Origin.OFFICIAL, "oa", "alice", "password", new byte[10]));
    }

    @Test
    void rejectsAKeyThatIsNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new CredentialCipher(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("master key must be 32 bytes");
    }

    private static void assertRejected(Runnable decrypt) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(decrypt::run);
        assertThat(thrown).isInstanceOf(CredentialDecryptException.class);
        assertThat(thrown.getCause()).isNull();
        SecretChecks.absent(thrown, PLAINTEXT);
    }

    private static byte[] testKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x3C);
        return key;
    }
}
