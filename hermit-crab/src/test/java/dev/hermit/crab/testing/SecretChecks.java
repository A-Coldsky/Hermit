package dev.hermit.crab.testing;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Assertions that do not print secret values when they fail. */
public final class SecretChecks {
    private SecretChecks() {
    }

    public static void absent(String haystack, String secret) {
        requireSecret(secret);
        if (haystack != null && haystack.contains(secret)) {
            throw new AssertionError("secret appeared in text of length " + haystack.length());
        }
    }

    public static void absent(Throwable throwable, String secret) {
        requireSecret(secret);
        Throwable current = throwable;
        while (current != null) {
            absent(current.getMessage(), secret);
            for (Throwable suppressed : current.getSuppressed()) {
                absent(suppressed, secret);
            }
            current = current.getCause();
        }
    }

    public static void sameMap(Map<String, String> actual, Map<String, String> expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("maps differ; actual keys " + keys(actual) + "; expected keys " + keys(expected));
        }
    }

    public static void contains(Set<String> values, String secret) {
        requireSecret(secret);
        if (values == null || !values.contains(secret)) {
            throw new AssertionError("expected secret to be present among " + (values == null ? 0 : values.size()) + " values");
        }
    }

    public static void missing(Set<String> values, String secret) {
        requireSecret(secret);
        if (values != null && values.contains(secret)) {
            throw new AssertionError("secret was still present among " + values.size() + " values");
        }
    }

    public static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void requireSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new AssertionError("test secret must not be empty");
        }
    }

    private static Object keys(Map<String, String> values) {
        return values == null ? null : values.keySet();
    }
}
