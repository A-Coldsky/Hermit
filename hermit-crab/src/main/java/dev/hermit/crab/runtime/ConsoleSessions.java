package dev.hermit.crab.runtime;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HexFormat;

/**
 * In-memory console sessions. The database does not store the token.
 * Twelve hours is an absolute deadline from issue, and {@link #clear()} drops every token.
 */
public final class ConsoleSessions {
    public static final String COOKIE = "hermit_console";
    public static final Duration TTL = Duration.ofHours(12);
    /** Missing or expired cookie. Wrong passwords use {@code ConsolePassword.FAILURE_MESSAGE}. */
    public static final String LOGIN_REQUIRED = "需要控制台登录。";

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Instant> expiry = new ConcurrentHashMap<>();

    public ConsoleSessions(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String issue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant now = clock.instant();
        // 过期的会话只有被拿来校验时才删，没人再用的会一直留着。签发时顺手清掉。
        expiry.values().removeIf(until -> !until.isAfter(now));
        expiry.put(token, now.plus(TTL));
        return token;
    }

    int size() {
        return expiry.size();
    }

    public boolean valid(String token) {
        if (token == null || token.length() != 64) {
            return false;
        }
        Instant until = expiry.get(token);
        if (until == null) {
            return false;
        }
        if (!until.isAfter(clock.instant())) {
            expiry.remove(token, until);
            return false;
        }
        return true;
    }

    public void clear() {
        expiry.clear();
    }

    /** HttpOnly, SameSite=Strict, Path=/console. No Secure attribute: the console is loopback HTTP. */
    public static String setCookie(String token) {
        return COOKIE + "=" + token
                + "; Max-Age=" + TTL.toSeconds()
                + "; Path=/console; HttpOnly; SameSite=Strict";
    }
}
