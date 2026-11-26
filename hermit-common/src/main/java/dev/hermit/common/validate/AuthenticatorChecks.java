package dev.hermit.common.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.version.SemVer;

/**
 * 授权器（{@code driver: auth}）和引用它的业务包（{@code auth.use}）的静态校验，1.13.0 起。
 *
 * <p>授权器只负责登录：有凭证、登录步骤、探活和导出的登录变量，没有 {@code base_url} 和能力。
 * 业务包不带凭证和登录步骤，用 {@code {{auth.名字}}} 读授权器导出的变量，可以写 {@code auth.exchange}
 * 在授权器登录之后换自己的票据。导出变量一律按秘密处理。
 *
 * <p>这里只看单个包。两个包之间的检查（授权器是否存在、版本、导出名）见 {@link #link}。
 */
public final class AuthenticatorChecks {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.13.0");
    public static final String DRIVER = "auth";
    /** {@code auth.allow_hosts} 最多写多少项。 */
    public static final int MAX_ALLOW_HOSTS = 32;
    /** {@code auth.exports} 最多写多少项。 */
    public static final int MAX_EXPORTS = 16;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern WILDCARD = Pattern.compile(
            "https?://\\*\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]{1,5})?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL);
    private static final Pattern AUTH_REF = Pattern.compile("(?<![\\w.])auth\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern BARE_REF = Pattern.compile("auth\\.([A-Za-z_][A-Za-z0-9_]*)(?:[.\\[].*)?");
    private static final Pattern SCRIPT_REF = Pattern.compile("\\bvars\\.auth\\.([A-Za-z_][A-Za-z0-9_]*)");

    private AuthenticatorChecks() {
    }

    public static boolean isAuthenticator(ConchDoc doc) {
        return doc != null && DRIVER.equals(doc.driver());
    }

    /** 业务包是否引用了授权器。 */
    public static boolean uses(ConchDoc doc) {
        return doc != null && "http".equals(doc.driver()) && doc.auth() != null && doc.auth().use() != null;
    }

    static void version(ConchDoc doc, List<ValidationIssue> errors) {
        boolean shared = doc.auth() != null && doc.auth().sharedDeclared();
        if ((isAuthenticator(doc) || shared) && doc.minCrabVersion().compareTo(MIN_CRAB_VERSION) < 0) {
            errors.add(ConchValidator.issue(
                    ValidationCodes.MIN_CRAB_VERSION,
                    "min_crab_version",
                    isAuthenticator(doc)
                            ? "driver 为 auth 时 min_crab_version 必须不低于 1.13.0"
                            : "使用 auth.use、min_version、exports、allow_hosts 或 exchange 时"
                                    + " min_crab_version 必须不低于 1.13.0"));
        }
    }

    /** {@code loginVars} 是登录步骤（授权器）或换票步骤（业务包）能留下来的变量名。 */
    static void check(
            ConchDoc doc, Set<String> loginVars, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        if (isAuthenticator(doc)) {
            authenticator(doc, loginVars, errors, warnings);
            return;
        }
        Auth auth = doc.auth();
        if (auth == null || !auth.sharedDeclared()) {
            return;
        }
        if (!auth.exports().isEmpty()) {
            errors.add(issue("auth.exports", "只有 driver 为 auth 的包才能写 auth.exports"));
        }
        if (!auth.allowHosts().isEmpty()) {
            errors.add(issue("auth.allow_hosts", "只有 driver 为 auth 的包才能写 auth.allow_hosts"));
        }
        if (auth.use() == null) {
            if (auth.minVersion() != null) {
                errors.add(issue("auth.min_version", "没有写 auth.use，不能写 auth.min_version"));
            }
            if (!auth.exchange().isEmpty()) {
                errors.add(issue("auth.exchange", "没有写 auth.use，不能写 auth.exchange"));
            }
            return;
        }
        consumer(doc, errors);
    }

    private static void authenticator(
            ConchDoc doc, Set<String> loginVars, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        if (doc.baseUrl() != null) {
            errors.add(issue("base_url", "driver 为 auth 时不能写 base_url，地址写在 auth.origins"));
        }
        if (doc.config().containsKey("base_url")) {
            errors.add(issue("config.base_url", "driver 为 auth 时不能声明 config.base_url"));
        }
        if (!doc.actions().isEmpty()) {
            errors.add(issue("actions", "driver 为 auth 时不能写 actions，授权器只负责登录"));
        }
        if (doc.groupsDeclared() || !doc.groups().isEmpty()) {
            errors.add(issue("groups", "driver 为 auth 时不能写 groups"));
        }
        if (!doc.healthCheck().isEmpty()) {
            errors.add(issue("health_check", "driver 为 auth 时不能写 health_check，用 auth.alive_check"));
        }
        Auth auth = doc.auth();
        if (auth == null) {
            errors.add(issue("auth", "driver 为 auth 时必须写 auth"));
            return;
        }
        if (auth.use() != null) {
            errors.add(issue("auth.use", "授权器不能再引用别的授权器"));
        }
        if (auth.minVersion() != null) {
            errors.add(issue("auth.min_version", "授权器不能写 auth.min_version"));
        }
        if (!auth.exchange().isEmpty()) {
            errors.add(issue("auth.exchange", "授权器不能写 auth.exchange，登录写在 auth.steps"));
        }
        if (auth.concurrentReads()) {
            errors.add(issue("auth.concurrent_reads", "授权器没有能力，不能写 auth.concurrent_reads"));
        }
        if (auth.expiredWhenDeclared()) {
            errors.add(issue("auth.expired_when", "授权器没有能力，不能写 auth.expired_when"));
        }
        if (auth.origins().isEmpty()) {
            errors.add(issue("auth.origins", "driver 为 auth 时必须写 auth.origins"));
        }
        if (auth.steps().isEmpty()) {
            errors.add(issue("auth.steps", "driver 为 auth 时必须写 auth.steps"));
        }
        if (auth.aliveCheck() == null) {
            errors.add(issue("auth.alive_check", "driver 为 auth 时必须写 auth.alive_check"));
        }
        exports(auth, loginVars, errors, warnings);
        allowHosts(doc, errors);
    }

    private static void exports(
            Auth auth, Set<String> loginVars, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        if (auth.exports().size() > MAX_EXPORTS) {
            errors.add(issue("auth.exports", "最多导出 " + MAX_EXPORTS + " 个变量"));
        }
        for (String name : auth.exports().keySet()) {
            String path = "auth.exports." + name;
            if (!IDENTIFIER.matcher(name).matches()) {
                errors.add(issue(path, "导出名只能用字母、数字和下划线，不能以数字开头"));
            } else if (!loginVars.contains(name) && !auth.cookies().containsKey(name)) {
                errors.add(issue(path, "登录步骤没有留下变量 " + name + "，auth.cookies 里也没有这个名字"));
            }
        }
        if (auth.exports().isEmpty() || auth.aliveCheck() == null) {
            return;
        }
        String request = String.valueOf(auth.aliveCheck().request());
        Set<String> probed = new LinkedHashSet<>();
        Matcher template = TEMPLATE.matcher(request);
        while (template.find()) {
            Matcher ref = Pattern.compile("(?<![\\w.])login\\.([A-Za-z_][A-Za-z0-9_]*)").matcher(template.group(1));
            while (ref.find()) {
                probed.add(ref.group(1));
            }
        }
        if (auth.exports().keySet().stream().noneMatch(probed::contains)) {
            warnings.add(issue("auth.alive_check.request",
                    "alive_check 没有用到任何导出变量。导出的令牌比会话先过期时探活仍会通过，"
                            + "业务包只能靠兜底重新登录恢复；建议探活请求带上 {{login.导出名}}"));
        }
    }

    private static void allowHosts(ConchDoc doc, List<ValidationIssue> errors) {
        List<String> hosts = doc.auth().allowHosts();
        if (hosts.size() > MAX_ALLOW_HOSTS) {
            errors.add(issue("auth.allow_hosts", "最多写 " + MAX_ALLOW_HOSTS + " 项"));
        }
        for (int i = 0; i < hosts.size(); i++) {
            String path = "auth.allow_hosts[" + i + "]";
            String entry = hosts.get(i) == null ? "" : hosts.get(i).trim();
            String name = AuthOrigins.configName(entry);
            if (name != null) {
                ConfigField field = doc.config().get(name);
                if (field == null || !"url".equals(field.type())) {
                    errors.add(issue(path, "config." + name + " 必须是声明过的 type: url 配置项"));
                }
                continue;
            }
            if (entry.contains("*")) {
                if (entry.contains("@")) {
                    errors.add(issue(path, "不能带用户名或密码"));
                } else if (!WILDCARD.matcher(entry).matches()) {
                    errors.add(issue(path, "通配只能写成 scheme://*.域名[:port]，星号后面至少两级域名，"
                            + "例如 https://*.corp.example.com"));
                }
                continue;
            }
            String problem = AuthOrigins.literalProblem(entry);
            if (problem != null) {
                errors.add(issue(path, problem));
            }
        }
    }

    private static void consumer(ConchDoc doc, List<ValidationIssue> errors) {
        Auth auth = doc.auth();
        if (!ConchValidator.isConchName(auth.use())) {
            errors.add(issue("auth.use", "auth.use 要写授权器的包名，符合 ^[a-z][a-z0-9_]{1,63}$"));
        } else if (auth.use().equals(doc.conch())) {
            errors.add(issue("auth.use", "auth.use 不能引用自己"));
        }
        if (auth.minVersion() != null) {
            try {
                SemVer.parse(auth.minVersion());
            } catch (RuntimeException ex) {
                errors.add(issue("auth.min_version", "auth.min_version 要写成 1.2.3 这样的版本号"));
            }
        }
        if (doc.credentials() != null && !doc.credentials().isEmpty()) {
            errors.add(issue("credentials", "业务包不能声明 credentials，凭证由授权器保管"));
        }
        if (!auth.steps().isEmpty()) {
            errors.add(issue("auth.steps", "业务包不能写 auth.steps；登录后要换票据时写 auth.exchange"));
        }
        if (auth.originsDeclared()) {
            errors.add(issue("auth.origins", "业务包不能写 auth.origins，认证地址由授权器声明"));
        }
        reserved(auth.exchange(), "auth.exchange", errors);
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            if (entry.getValue() != null) {
                reserved(entry.getValue().steps(), "actions." + entry.getKey() + ".steps", errors);
            }
        }
    }

    /** 业务包里 {@code auth} 是保留的变量根，步骤不能写入同名变量。 */
    private static void reserved(List<Step> steps, String path, List<ValidationIssue> errors) {
        for (Step step : FlowSteps.flat(steps)) {
            boolean named = switch (step.type()) {
                case "extract", "set" -> step.def().containsKey(DRIVER);
                case "script", "ask" -> DRIVER.equals(step.def().get("into"));
                case "repeat" -> step.def().get("collect") instanceof Map<?, ?> collect && collect.containsKey(DRIVER);
                default -> false;
            };
            if (named) {
                errors.add(ConchValidator.issue(ValidationCodes.RESERVED_VARIABLE, path,
                        "业务包里不能写入保留变量 auth"));
            }
        }
    }

    /** 判断里不带 {@code {{ }}} 的变量名。只接收判断本身（assert、if、until、expect、expired_when 的一条），不往请求体里找。 */
    private static void conditionReferences(Map<?, ?> condition, Set<String> names) {
        for (Map.Entry<?, ?> entry : condition.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if ("present".equals(key) || "not_present".equals(key)) {
                for (Object name : value instanceof Collection<?> list ? list : Collections.singletonList(value)) {
                    bareName(name, names);
                }
            } else if (("equals".equals(key) || "not_equals".equals(key)) && value instanceof Map<?, ?> pairs) {
                pairs.keySet().forEach(name -> bareName(name, names));
            }
        }
    }

    /** 步骤里的判断和脚本：assert、if、repeat 的 until 里直接写的变量名，script 里的 {@code vars.auth.名字}。 */
    private static void stepReferences(List<Step> steps, Set<String> names) {
        for (Step step : FlowSteps.flat(steps)) {
            switch (step.type()) {
                case "assert", FlowSteps.IF -> conditionReferences(step.def(), names);
                case FlowSteps.REPEAT -> {
                    if (step.def().get("until") instanceof Map<?, ?> until) {
                        conditionReferences(until, names);
                    }
                }
                case "script" -> {
                    Matcher script = SCRIPT_REF.matcher(String.valueOf(step.def()));
                    while (script.find()) {
                        names.add(script.group(1));
                    }
                }
                default -> {
                    // 其它步骤只可能通过模板引用。
                }
            }
        }
    }

    private static void bareName(Object name, Set<String> names) {
        if (name instanceof String text) {
            Matcher ref = BARE_REF.matcher(text);
            if (ref.matches()) {
                names.add(ref.group(1));
            }
        }
    }

    /**
     * 业务包里用到的授权器导出名（顺序不保证）。模板 {@code {{auth.名字}}}、脚本里的 {@code vars.auth.名字}，
     * 以及判断键（{@code present}、{@code not_present}、{@code equals}、{@code not_equals}）里直接写的
     * 变量名 {@code auth.名字} 都算。
     */
    public static Set<String> referencedExports(ConchDoc doc) {
        return referencedExports(doc, doc.actions().values());
    }

    /** 同上，但能力只算给出的这几个；换票、探活和失效判断里用到的总是算。 */
    public static Set<String> referencedExports(ConchDoc doc, Collection<Action> actions) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (!uses(doc)) {
            return names;
        }
        ArrayList<Object> sources = new ArrayList<>();
        sources.add(doc.auth().exchange());
        stepReferences(doc.auth().exchange(), names);
        if (doc.auth().aliveCheck() != null) {
            sources.add(doc.auth().aliveCheck().request());
            sources.add(doc.auth().aliveCheck().expectNotRedirect());
            sources.add(doc.auth().aliveCheck().expect());
            conditionReferences(doc.auth().aliveCheck().expect(), names);
        }
        sources.add(doc.auth().expiredWhen());
        doc.auth().expiredWhen().forEach(condition -> conditionReferences(condition, names));
        for (Action action : actions) {
            if (action != null) {
                sources.add(action.steps());
                sources.add(action.output());
                stepReferences(action.steps(), names);
            }
        }
        for (Object source : sources) {
            Matcher template = TEMPLATE.matcher(String.valueOf(source));
            while (template.find()) {
                Matcher ref = AUTH_REF.matcher(template.group(1));
                while (ref.find()) {
                    names.add(ref.group(1));
                }
            }
        }
        return names;
    }

    /**
     * 业务包和它引用的授权器对不对得上。{@code authenticator} 是这台机器上（或 Reef 上）同名的包，没有时传 null。
     * 返回的每一项都是阻止使用的错误；地址范围（{@code allow_hosts}、换票步骤能访问哪里）要看每台 Crab 的配置，不在这里。
     */
    public static List<ValidationIssue> link(ConchDoc consumer, ConchDoc authenticator) {
        ArrayList<ValidationIssue> problems = new ArrayList<>();
        if (!uses(consumer)) {
            return problems;
        }
        String name = consumer.auth().use();
        if (authenticator == null) {
            problems.add(issue("auth.use", "找不到授权器 " + name));
            return problems;
        }
        if (!isAuthenticator(authenticator) || !name.equals(authenticator.conch())) {
            problems.add(issue("auth.use", name + " 不是授权器（driver: auth）"));
            return problems;
        }
        String min = consumer.auth().minVersion();
        if (min != null) {
            try {
                if (authenticator.version().compareTo(SemVer.parse(min)) < 0) {
                    problems.add(issue("auth.min_version",
                            "授权器 " + name + " 的版本是 " + authenticator.version() + "，低于要求的 " + min));
                }
            } catch (RuntimeException ex) {
                problems.add(issue("auth.min_version", "auth.min_version 要写成 1.2.3 这样的版本号"));
            }
        }
        Set<String> exported = authenticator.auth() == null ? Set.of() : authenticator.auth().exports().keySet();
        for (String used : referencedExports(consumer)) {
            if (!exported.contains(used)) {
                problems.add(issue("auth.use", "授权器 " + name + " 没有导出变量 " + used));
            }
        }
        return problems;
    }

    private static ValidationIssue issue(String path, String message) {
        return ConchValidator.issue(ValidationCodes.AUTHENTICATOR, path, message);
    }
}
