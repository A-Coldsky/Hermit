package dev.hermit.common.validate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.Step;

/**
 * 一份规则包静态能发往的地址。每项是规范化后的 {@code scheme://host:port}。
 * {@code auth.origins} 里的认证地址计入：字面量直接算，引用配置项的算它的默认值。
 * 新集合是旧集合的子集，且两边都能静态确定、驱动/授权引用/MCP 认证类型没变、授权器 {@code allow_hosts} 未放宽，才是同一边界。
 *
 * <p>集合里有两类地址：请求真正能发过去的（{@code base_url}、认证地址、换票请求地址、MCP 上游和它的认证端点），
 * 和只是在步骤文本里出现过的。旧版本只在文本里提到过的地址，新版本把它变成能发请求的地址，
 * 同样算扩大：凭证从这一版起才会发到那里。新引用一个配置项当认证地址或允许主机也算。
 *
 * <p>OAuth 的 issuer 和 registration 目前不是包字段，发现到的端点仍由上游允许主机在运行时拦住。
 * {@code allowed_hosts} 本身不计入这个集合。{@code health_check} 没有执行链，不计入。
 */
public final class ConchDestinations {

    private static final Set<String> URL_KEYS = Set.of("url", "path", "base", "base_url");
    private static final String UNDETERMINED = "无法静态确定的地址";

    private ConchDestinations() {
    }

    /**
     * {@code before == null}（旧文档读不出来）按扩大处理，并标成无法静态确定。
     * {@code addedDestinations} 已按展示文本排序。
     */
    public static Boundary between(ConchDoc before, ConchDoc after) {
        if (after == null) {
            throw new IllegalArgumentException("after");
        }
        Analysis next = analyze(after);
        if (before == null) {
            return new Boundary(false, texts(next.destinations), true, false);
        }
        Analysis previous = analyze(before);
        boolean newAllowConfig = "auth".equals(previous.driver)
                && "auth".equals(next.driver)
                && previous.allowHostsRestricted
                && !previous.allowHostConfigs.keySet().containsAll(next.allowHostConfigs.keySet());
        boolean authChanged = !Objects.equals(previous.driver, next.driver)
                || !Objects.equals(previous.authType, next.authType)
                || !Objects.equals(previous.authUse, next.authUse)
                || allowHostsWidened(previous, next);
        // 新引用的配置项：地址是这台 Crab 上填的值，静态看不出来。
        boolean newAuthConfig = !previous.authConfig.containsAll(next.authConfig) || newAllowConfig;
        boolean undetermined = previous.undetermined || next.undetermined || newAuthConfig;
        boolean same = !authChanged
                && !undetermined
                && previous.destinations.containsAll(next.destinations)
                && previous.reachable.containsAll(next.reachable);
        TreeSet<Destination> added = new TreeSet<>(next.destinations);
        added.removeAll(previous.destinations);
        TreeSet<Destination> promoted = new TreeSet<>(next.reachable);
        promoted.removeAll(previous.reachable);
        added.addAll(promoted);
        return new Boundary(same, texts(added), undetermined, authChanged);
    }

    private static boolean allowHostsWidened(Analysis previous, Analysis next) {
        if (!"auth".equals(previous.driver) || !"auth".equals(next.driver)) {
            return false;
        }
        // 旧版未写 allow_hosts 代表不限制允许站点，新版写什么都只会收紧或维持不变。
        if (!previous.allowHostsRestricted) {
            return false;
        }
        // 旧版限制了 allow_hosts，新版删空则变为不限制（放宽）。
        if (!next.allowHostsRestricted) {
            return true;
        }
        if (!previous.allowHostLiterals.containsAll(next.allowHostLiterals)) {
            return true;
        }
        for (Map.Entry<String, String> entry : next.allowHostConfigs.entrySet()) {
            if (!previous.allowHostConfigs.containsKey(entry.getKey())
                    || !Objects.equals(previous.allowHostConfigs.get(entry.getKey()), entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** 用顿号连接新增地址。有无法静态确定的部分时另加一句；两边都空时也用那句。 */
    public static String describe(Boundary boundary) {
        if (boundary == null) {
            return UNDETERMINED;
        }
        String joined = String.join("、", boundary.addedDestinations());
        if (boundary.undetermined()) {
            return joined.isEmpty() ? UNDETERMINED : joined + "、" + UNDETERMINED;
        }
        return joined.isEmpty() ? UNDETERMINED : joined;
    }

    private static Analysis analyze(ConchDoc doc) {
        Analysis analysis = new Analysis();
        analysis.driver = doc.driver();
        analysis.reach(doc.baseUrl());
        ConfigField configured = doc.config().get("base_url");
        if (configured != null && configured.defaultValue() instanceof String text) {
            analysis.reach(text);
        }
        if (doc.auth() != null) {
            analysis.authUse = doc.auth().use();
            for (String origin : doc.auth().origins()) {
                String name = AuthOrigins.configName(origin);
                if (name == null) {
                    analysis.reach(origin);
                    continue;
                }
                // 引用配置项：地址由这台 Crab 的使用者填，和 config.base_url 一样只算默认值。
                analysis.authConfig.add(name);
                ConfigField field = doc.config().get(name);
                if (field != null && field.defaultValue() instanceof String text) {
                    analysis.reach(text);
                }
            }
            for (Step step : doc.auth().steps()) {
                analysis.walk(step.def());
            }
            for (Step step : FlowSteps.flat(doc.auth().exchange())) {
                if ("request".equals(step.type())) {
                    analysis.walkExchangeRequest(step.def());
                } else if (!FlowSteps.isFlow(step)) {
                    analysis.walk(step.def());
                } else {
                    analysis.walk(FlowSteps.own(step));
                }
            }
            if (doc.auth().aliveCheck() != null) {
                analysis.walk(doc.auth().aliveCheck().request());
            }
            if (AuthenticatorChecks.isAuthenticator(doc) && !doc.auth().allowHosts().isEmpty()) {
                analysis.allowHostsRestricted = true;
                for (String raw : doc.auth().allowHosts()) {
                    String entry = raw == null ? "" : raw.trim();
                    String cfg = AuthOrigins.configName(entry);
                    if (cfg != null) {
                        ConfigField field = doc.config().get(cfg);
                        String def = field != null && field.defaultValue() instanceof String text
                                ? SiteAddress.of(text)
                                : null;
                        analysis.allowHostConfigs.put(cfg, def == null ? "" : def);
                    } else if (entry.contains("*")) {
                        analysis.allowHostLiterals.add(normalizeWildcard(entry));
                    } else {
                        String site = SiteAddress.of(entry);
                        analysis.allowHostLiterals.add(site == null ? entry.toLowerCase(Locale.ROOT) : site);
                    }
                }
            }
        }
        for (Action action : doc.actions().values()) {
            for (Step step : action.steps()) {
                analysis.walk(step.def());
            }
        }
        for (String source : doc.scripts().values()) {
            analysis.take(source, false);
        }
        if (doc.upstream() != null) {
            analysis.reach(doc.upstream().url());
            McpAuth auth = doc.upstream().auth();
            if (auth != null) {
                analysis.reach(auth.authorizationEndpoint());
                analysis.reach(auth.tokenEndpoint());
                analysis.authType = auth.type() == null || auth.type().isBlank() ? null : auth.type();
            }
        }
        return analysis;
    }

    private static String normalizeWildcard(String entry) {
        try {
            URI parsed = URI.create(entry.replace("*.", "x."));
            if (parsed.getScheme() != null && parsed.getHost() != null) {
                String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
                int port = parsed.getPort() >= 0 ? parsed.getPort() : ("https".equals(scheme) ? 443 : 80);
                String suffix = parsed.getHost().substring(1).toLowerCase(Locale.ROOT);
                return scheme + "://*" + suffix + ":" + port;
            }
        } catch (RuntimeException ignored) {
            // 非法通配由 ConchValidator 拒绝，这里退回小写原串兜底。
        }
        return entry.toLowerCase(Locale.ROOT);
    }

    private static List<String> texts(Set<Destination> destinations) {
        ArrayList<String> texts = new ArrayList<>();
        for (Destination destination : destinations) {
            texts.add(destination.text());
        }
        return List.copyOf(texts);
    }

    /**
     * @param sameBoundary 新目的地是旧目的地的子集（含相等），请求能发过去的地址也没有变多，
     *                     且没有无法静态确定的地址，驱动/认证方式/授权器引用没变，allow_hosts 未放宽
     * @param addedDestinations 新出现的，或从这一版起请求才能发过去的 {@code scheme://host:port}，已排序
     * @param undetermined 任一侧有 scheme 或主机里的模板，旧文档读不出来，或新版本新引用了配置项当认证/允许地址
     * @param authChanged 驱动、MCP {@code auth.type}、{@code auth.use} 变化，或授权器 {@code allow_hosts} 放宽
     */
    public record Boundary(
            boolean sameBoundary,
            List<String> addedDestinations,
            boolean undetermined,
            boolean authChanged
    ) {
        public Boundary {
            addedDestinations = addedDestinations == null ? List.of() : List.copyOf(addedDestinations);
        }
    }

    private record Destination(String scheme, String host, int port) implements Comparable<Destination> {
        String text() {
            String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return scheme + "://" + shown + ":" + port;
        }

        @Override
        public int compareTo(Destination other) {
            return text().compareTo(other.text());
        }
    }

    private static final class Analysis {
        private final TreeSet<Destination> destinations = new TreeSet<>();
        /** 请求真正能发过去的地址，是 {@link #destinations} 的子集。 */
        private final TreeSet<Destination> reachable = new TreeSet<>();
        private boolean undetermined;
        private String driver;
        private String authType;
        private String authUse;
        private boolean allowHostsRestricted;
        private final TreeSet<String> allowHostLiterals = new TreeSet<>();
        private final Map<String, String> allowHostConfigs = new LinkedHashMap<>();
        /** auth.origins 引用的配置项。以它开头的地址已经算在认证地址里，不算无法静态确定。 */
        private final Set<String> authConfig = new TreeSet<>();

        /** 包声明的目标地址：计入集合，并记成请求能发过去的。 */
        private void reach(String value) {
            take(value, true);
            if (value == null || value.isBlank() || templatedOrigin(value.trim()) || !startsWithHttp(value.trim())) {
                return;
            }
            McpAddresses.ParseResult parsed = McpAddresses.parse(value.trim());
            if (parsed.ok()) {
                McpAddresses.Parsed url = parsed.url();
                reachable.add(new Destination(url.scheme(), url.host(), url.port()));
            }
        }

        private void walkExchangeRequest(Map<String, Object> request) {
            for (Map.Entry<String, Object> entry : request.entrySet()) {
                Object value = entry.getValue();
                if (URL_KEYS.contains(entry.getKey()) && value instanceof String text) {
                    reach(text);
                } else {
                    walk(value);
                }
            }
        }

        private void walk(Object node) {
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object value = entry.getValue();
                    if (URL_KEYS.contains(String.valueOf(entry.getKey())) && value instanceof String text) {
                        take(text, true);
                    } else {
                        walk(value);
                    }
                }
                return;
            }
            if (node instanceof List<?> list) {
                for (Object item : list) {
                    walk(item);
                }
                return;
            }
            if (node instanceof String text) {
                take(text, false);
            }
        }

        private void take(String value, boolean urlField) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (urlField) {
                considerWhole(value.trim());
            }
            scanEmbedded(value);
        }

        private void considerWhole(String value) {
            String configured = AuthOrigins.leadingConfigName(value);
            if (configured != null && authConfig.contains(configured)) {
                return;
            }
            if (templatedOrigin(value)) {
                undetermined = true;
                return;
            }
            if (!startsWithHttp(value)) {
                return;
            }
            addLiteral(value);
        }

        private void scanEmbedded(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from < text.length()) {
                int https = lower.indexOf("https://", from);
                int http = lower.indexOf("http://", from);
                int at = earliest(https, http);
                if (at < 0) {
                    return;
                }
                int end = at;
                while (end < text.length()) {
                    char c = text.charAt(end);
                    if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>'
                            || c == ')' || c == ']' || c == ',') {
                        break;
                    }
                    end++;
                }
                considerWhole(text.substring(at, end));
                from = Math.max(at + 1, end);
            }
        }

        private void addLiteral(String value) {
            McpAddresses.ParseResult parsed = McpAddresses.parse(value);
            if (parsed.ok()) {
                McpAddresses.Parsed url = parsed.url();
                destinations.add(new Destination(url.scheme(), url.host(), url.port()));
                return;
            }
            undetermined = true;
        }

        private static boolean startsWithHttp(String value) {
            return value.regionMatches(true, 0, "https://", 0, "https://".length())
                    || value.regionMatches(true, 0, "http://", 0, "http://".length());
        }

        /** scheme 或主机里有模板，或整个 URL 字段是模板。相对路径里的 {@code {{id}}} 不算。 */
        private static boolean templatedOrigin(String value) {
            int schemeEnd = value.indexOf("://");
            if (schemeEnd < 0) {
                return value.contains("{{") && !value.startsWith("/");
            }
            if (value.substring(0, schemeEnd).contains("{{")) {
                return true;
            }
            String rest = value.substring(schemeEnd + 3);
            int cut = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    cut = i;
                    break;
                }
            }
            String hostPort = rest.substring(0, cut);
            int at = hostPort.lastIndexOf('@');
            if (at >= 0) {
                hostPort = hostPort.substring(at + 1);
            }
            String host = hostPort;
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                host = end < 0 ? host : host.substring(0, end + 1);
            } else {
                int colon = host.indexOf(':');
                if (colon >= 0) {
                    host = host.substring(0, colon);
                }
            }
            return host.contains("{{");
        }

        private static int earliest(int left, int right) {
            if (left < 0) {
                return right;
            }
            if (right < 0) {
                return left;
            }
            return Math.min(left, right);
        }
    }
}
