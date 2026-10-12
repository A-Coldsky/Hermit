package dev.hermit.crab.runtime;

/** Visible ASCII, 1 to 128 characters. Space and controls are rejected. */
public final class IdempotencyKeys {
    public static final int MAX_LENGTH = 128;

    private IdempotencyKeys() {
    }

    public static boolean valid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
