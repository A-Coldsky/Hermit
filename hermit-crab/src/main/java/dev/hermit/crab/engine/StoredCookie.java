package dev.hermit.crab.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param hostOnly 响应没写 Domain：只发给设置它的那个主机，不发给子域。
 *                 这个字段加入之前存下的 Cookie 读出来是 false，和原来一样按域名匹配。
 */
public record StoredCookie(
        String name,
        String value,
        String domain,
        String path,
        Long expiryEpochMillis,
        boolean secure,
        boolean httpOnly,
        boolean hostOnly
) {
    public StoredCookie(
            String name,
            String value,
            String domain,
            String path,
            Long expiryEpochMillis,
            boolean secure,
            boolean httpOnly
    ) {
        this(name, value, domain, path, expiryEpochMillis, secure, httpOnly, false);
    }

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @Override
    public boolean hostOnly() {
        return hostOnly;
    }
}
