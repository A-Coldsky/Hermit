package dev.hermit.crab.gateway;

/**
 * Console callers must come from the local machine. Forwarded-for headers are ignored;
 * only the socket address counts.
 */
public final class Loopback {
    private Loopback() {
    }

    public static boolean address(String remote) {
        if (remote == null) {
            return false;
        }
        String value = remote.trim();
        return "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value)
                || "::ffff:127.0.0.1".equalsIgnoreCase(value);
    }
}
