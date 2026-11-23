package dev.hermit.crab.engine;

import org.apache.hc.client5.http.cookie.BasicCookieStore;

/** 身份的 Cookie 罐：里面有别的成员拿到的 Cookie，按名字读时比独立的罐多看一层（见 {@link AuthReach#readable}）。 */
final class SharedCookieStore extends BasicCookieStore {
    private static final long serialVersionUID = 1L;
}
