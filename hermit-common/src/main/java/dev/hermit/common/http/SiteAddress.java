package dev.hermit.common.http;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ConfigField;

/**
 * 站点地址：规范成 {@code scheme://host:port}。主机转小写、去掉结尾的点，没写端口时补默认端口。
 * 两个 {@code driver: http} 的规则包站点地址相同，就是访问同一个网站。只用来提示，不代表可以共用登录。
 */
public final class SiteAddress {

    private SiteAddress() {
    }

    /** 不是 http、https 的绝对地址，或读不出主机时返回 null。 */
    public static String of(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return of(URI.create(url.trim()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static String of(URI url) {
        URI uri = HostNames.withoutTrailingDot(url);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (scheme.equals("http")) {
            port = port < 0 ? 80 : port;
        } else if (scheme.equals("https")) {
            port = port < 0 ? 443 : port;
        } else {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return scheme + "://" + host + ":" + port;
    }

    /**
     * 包里写死的站点：{@code base_url} 和配置项 {@code base_url} 的默认值。
     * 每台 Crab 另填的地址不在包里，这里看不到。{@code driver: mcp} 的包为空。
     */
    public static Set<String> declared(ConchDoc doc) {
        LinkedHashSet<String> sites = new LinkedHashSet<>();
        if (doc == null || "mcp".equals(doc.driver())) {
            return sites;
        }
        add(sites, doc.baseUrl());
        ConfigField configured = doc.config().get("base_url");
        if (configured != null && configured.defaultValue() instanceof String text) {
            add(sites, text);
        }
        return sites;
    }

    /** 实际生效的站点：配置里填了 {@code base_url} 用它，否则用包里的。和规则引擎取地址的顺序一致。 */
    public static String effective(ConchDoc doc, String configuredBaseUrl) {
        if (doc == null || "mcp".equals(doc.driver())) {
            return null;
        }
        boolean configured = configuredBaseUrl != null && !configuredBaseUrl.isBlank();
        return of(configured ? configuredBaseUrl : doc.baseUrl());
    }

    private static void add(Set<String> sites, String url) {
        String site = of(url);
        if (site != null) {
            sites.add(site);
        }
    }
}
