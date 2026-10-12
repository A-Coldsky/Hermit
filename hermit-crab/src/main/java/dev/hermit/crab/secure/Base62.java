package dev.hermit.crab.secure;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Base62 with the alphabet {@code 0-9A-Za-z}. Each leading zero byte becomes one {@code 0}
 * so a 32-byte value keeps its full entropy and round-trips.
 */
final class Base62 {
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int[] INDEX = new int[128];

    static {
        Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEX[ALPHABET[i]] = i;
        }
    }

    private Base62() {
    }

    static String encode(byte[] data) {
        Objects.requireNonNull(data, "data");
        int zeros = 0;
        while (zeros < data.length && data[zeros] == 0) {
            zeros++;
        }
        StringBuilder digits = new StringBuilder();
        if (zeros < data.length) {
            BigInteger value = new BigInteger(1, data);
            BigInteger base = BigInteger.valueOf(ALPHABET.length);
            while (value.signum() > 0) {
                BigInteger[] divided = value.divideAndRemainder(base);
                digits.append(ALPHABET[divided[1].intValue()]);
                value = divided[0];
            }
            digits.reverse();
        }
        return "0".repeat(zeros) + digits;
    }

    static byte[] decode(String text) {
        Objects.requireNonNull(text, "text");
        int zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '0') {
            zeros++;
        }
        BigInteger value = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(ALPHABET.length);
        for (int i = zeros; i < text.length(); i++) {
            char character = text.charAt(i);
            int digit = character < INDEX.length ? INDEX[character] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base62");
            }
            value = value.multiply(base).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        if (magnitude.length > 0 && magnitude[0] == 0) {
            magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
        }
        byte[] decoded = new byte[zeros + magnitude.length];
        System.arraycopy(magnitude, 0, decoded, zeros, magnitude.length);
        return decoded;
    }
}
