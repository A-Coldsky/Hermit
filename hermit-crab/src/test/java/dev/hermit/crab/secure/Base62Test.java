package dev.hermit.crab.secure;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {
    @Test
    void encodesKnownValuesAndPreservesLeadingZeroBytes() {
        assertThat(Base62.encode(new byte[0])).isEmpty();
        assertThat(Base62.encode(new byte[] {0})).isEqualTo("0");
        assertThat(Base62.encode(new byte[] {1})).isEqualTo("1");
        assertThat(Base62.encode(new byte[] {10})).isEqualTo("A");
        assertThat(Base62.encode(new byte[] {36})).isEqualTo("a");
        assertThat(Base62.encode(new byte[] {61})).isEqualTo("z");
        assertThat(Base62.encode(new byte[] {62})).isEqualTo("10");
        assertThat(Base62.encode(new byte[] {(byte) 0xff})).isEqualTo("47");
        assertThat(Base62.encode(new byte[] {0, 1})).isEqualTo("01");
        assertThat(Base62.encode(new byte[32])).isEqualTo("0".repeat(32));
    }

    @Test
    void roundTripsRandomThirtyTwoByteValues() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 200; i++) {
            byte[] value = new byte[32];
            random.nextBytes(value);
            assertThat(Base62.decode(Base62.encode(value))).containsExactly(value);
        }
        byte[] leadingZero = new byte[32];
        leadingZero[1] = (byte) 0xff;
        assertThat(Base62.decode(Base62.encode(leadingZero))).containsExactly(leadingZero);
        byte[] highBit = new byte[32];
        highBit[0] = (byte) 0x80;
        assertThat(Base62.decode(Base62.encode(highBit))).containsExactly(highBit);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base62.decode("hk_")).isInstanceOf(IllegalArgumentException.class);
    }
}
