package dev.hermit.crab.engine;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.core5.net.InetAddressUtils;

import dev.hermit.common.http.HostNames;
import dev.hermit.common.http.SiteAddress;

/**
 * 这台 Crab 上生效的认证地址（{@code auth.origins}），以及当前是不是登录步骤。
 * 登录步骤可以请求认证地址，也可以在 {@code base_url} 和认证地址之间跟随跳转；
 * 能力步骤和探活不行，它们的响应跳到认证地址时算会话失效。
 *
 * @param origins {@link SiteAddress} 写法的认证地址
 */
record AuthReach(Set<String> origins, boolean login) {

    static final AuthReach NONE = new AuthReach(Set.of(), false);

    AuthReach {
        origins = origins == null ? Set.of() : Set.copyOf(origins);
    }

    static AuthReach of(Set<String> origins, boolean login) {
        return origins == null || origins.isEmpty() ? NONE : new AuthReach(origins, login);
    }

    boolean declares(URI target) {
        if (origins.isEmpty()) {
            return false;
        }
        // mailto: 之类没有主机的地址规范不出站点；不可变集合不接受 null。
        String site = SiteAddress.of(target);
        return site != null && origins.contains(site);
    }

    /** 登录步骤能不能请求这个地址：{@code base_url} 或声明过的认证地址。 */
    boolean allows(URI base, URI target) {
        return HttpCalls.sameOrigin(base, target) || (login && declares(target));
    }

    /**
     * 这个 Cookie 会不会发往 {@code base_url} 的主机，判断方式和 Cookie 罐一致：主机相同，
     * 或者 Cookie 写了 Domain 且主机是它的子域。规则按名字读 Cookie 时只看这些，
     * 认证地址上的 Cookie 读不到。不看路径和 Secure。
     */
    static boolean sentTo(Cookie cookie, URI base) {
        if (base == null || cookie.getDomain() == null) {
            return true;
        }
        URI uri = HostNames.withoutTrailingDot(base);
        if (uri.getHost() == null) {
            return true;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String domain = cookie.getDomain().toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        if (host.equals(domain)) {
            return true;
        }
        return cookie.containsAttribute(Cookie.DOMAIN_ATTR)
                && host.endsWith("." + domain)
                && !InetAddressUtils.isIPv4(host)
                && !InetAddressUtils.isIPv6(host);
    }
}
