package dev.hermit.crab.engine;

public record StoredCookie(
        String name,
        String value,
        String domain,
        String path,
        Long expiryEpochMillis,
        boolean secure,
        boolean httpOnly
) {
}
