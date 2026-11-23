package dev.hermit.common.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.version.SemVer;

/**
 * {@code auth.origins}：登录时除 {@code base_url} 之外还要访问的认证地址（门户、SSO）。
 * 每项是不带路径的 {@code scheme://host[:port]}，或整项写成 {@code {{config.NAME}}} 引用一个
 * {@code type: url} 的配置项。只有登录步骤能访问，能力步骤和探活仍只能访问 {@code base_url}。
 */
public final class AuthOrigins {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.11.0");
    public static final int MAX = 4;

    private static final Pattern CONFIG_REF =
            Pattern.compile("\\{\\{\\s*config\\.([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private AuthOrigins() {
    }

    public static boolean used(ConchDoc doc) {
        return doc != null && doc.auth() != null && !doc.auth().origins().isEmpty();
    }

    /** 整项是 {@code {{config.NAME}}} 时返回 NAME，否则返回 null。 */
    public static String configName(String entry) {
        if (entry == null) {
            return null;
        }
        Matcher matcher = CONFIG_REF.matcher(entry.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * 文本以 {@code {{config.NAME}}} 开头，后面是结尾、{@code /}、{@code ?} 或 {@code #} 时返回 NAME。
     * 登录步骤用这种写法拼出认证地址下的路径。
     */
    public static String leadingConfigName(String url) {
        if (url == null) {
            return null;
        }
        String text = url.trim();
        Matcher matcher = CONFIG_REF.matcher(text);
        if (!matcher.lookingAt()) {
            return null;
        }
        if (matcher.end() == text.length()) {
            return matcher.group(1);
        }
        char next = text.charAt(matcher.end());
        return next == '/' || next == '?' || next == '#' ? matcher.group(1) : null;
    }

    /** 字面量写法有问题时返回说明，没问题返回 null。 */
    public static String literalProblem(String entry) {
        if (entry == null || entry.isBlank()) {
            return "不能为空";
        }
        String text = entry.trim();
        if (text.contains("{{")) {
            return "只能整项写成 {{config.名字}}，不能和别的文字拼在一起";
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException ex) {
            return "不是有效的地址";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "必须以 http:// 或 https:// 开头";
        }
        if (SiteAddress.of(uri) == null) {
            return "读不出主机";
        }
        if (uri.getRawUserInfo() != null) {
            return "不能带用户名或密码";
        }
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "只写 scheme://host[:port]，不带路径和查询";
        }
        return null;
    }

    /**
     * 包里能静态读出的认证地址：字面量，以及引用的配置项的默认值。每项是 {@link SiteAddress} 的写法。
     * 每台 Crab 在配置里另填的地址这里看不到。
     */
    public static Set<String> declared(ConchDoc doc) {
        LinkedHashSet<String> sites = new LinkedHashSet<>();
        if (!used(doc)) {
            return sites;
        }
        for (String entry : doc.auth().origins()) {
            String name = configName(entry);
            if (name == null) {
                add(sites, entry);
                continue;
            }
            ConfigField field = doc.config().get(name);
            if (field != null && field.defaultValue() instanceof String text) {
                add(sites, text);
            }
        }
        return sites;
    }

    /**
     * 这台 Crab 上生效的认证地址。{@code config} 是已经合并了默认值的配置。
     * 引用的配置项没填或填的不是 http(s) 地址时，这一项不生效。
     */
    public static Set<String> effective(ConchDoc doc, Map<String, String> config) {
        LinkedHashSet<String> sites = new LinkedHashSet<>();
        if (!used(doc)) {
            return sites;
        }
        for (String entry : doc.auth().origins()) {
            String name = configName(entry);
            add(sites, name == null ? entry : config == null ? null : config.get(name));
        }
        return sites;
    }

    private static void add(Set<String> sites, String url) {
        String site = SiteAddress.of(url);
        if (site != null) {
            sites.add(site);
        }
    }
}
