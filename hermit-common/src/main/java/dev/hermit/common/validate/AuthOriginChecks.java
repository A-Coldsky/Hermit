package dev.hermit.common.validate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.hermit.common.http.AuthOrigins;
import dev.hermit.common.http.HostNames;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;

/**
 * {@code auth.origins} 的写法，以及哪些步骤能访问认证地址：登录步骤可以，能力步骤和探活不行。
 */
final class AuthOriginChecks {

    private static final Pattern CREDENTIAL = Pattern.compile("\\{\\{[^}]*\\bcredential\\.");

    private AuthOriginChecks() {
    }

    static void check(ConchDoc doc, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        if (doc.auth() == null) {
            return;
        }
        List<String> origins = doc.auth().origins();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (!origins.isEmpty()) {
            if (doc.minCrabVersion().compareTo(AuthOrigins.MIN_CRAB_VERSION) < 0) {
                errors.add(ConchValidator.issue(
                        ValidationCodes.MIN_CRAB_VERSION,
                        "min_crab_version",
                        "使用 auth.origins 时 min_crab_version 必须不低于 1.11.0"));
            }
            if (origins.size() > AuthOrigins.MAX) {
                errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, "auth.origins",
                        "最多写 " + AuthOrigins.MAX + " 个认证地址"));
            }
            for (int i = 0; i < origins.size(); i++) {
                entry(doc, origins.get(i), "auth.origins[" + i + "]", names, errors, warnings);
            }
        }
        Set<String> declared = AuthOrigins.declared(doc);
        Set<String> bases = SiteAddress.declared(doc);
        Scope scope = new Scope(declared, names, bases);
        loginSteps(doc.auth().steps(), "auth.steps", scope, false, 0, warnings);
        if (origins.isEmpty()) {
            return;
        }
        if (doc.auth().aliveCheck() != null) {
            forbidden(doc.auth().aliveCheck().request(), "auth.alive_check.request", scope,
                    "alive_check 只能访问 base_url，认证地址只能在 auth.steps 里访问", errors);
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            if (entry.getValue() != null) {
                actionSteps(entry.getValue().steps(), "actions." + entry.getKey() + ".steps", scope, 0, errors);
            }
        }
    }

    private static void entry(
            ConchDoc doc,
            String origin,
            String path,
            Set<String> names,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        String name = AuthOrigins.configName(origin);
        if (name == null) {
            String problem = AuthOrigins.literalProblem(origin);
            if (problem != null) {
                errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, path, problem));
                return;
            }
            host(path, origin, errors, warnings);
            if (SiteAddress.declared(doc).contains(SiteAddress.of(origin))) {
                warnings.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, path,
                        "和 base_url 是同一个地址，不用写进 auth.origins"));
            }
            return;
        }
        if ("base_url".equals(name)) {
            errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, path,
                    "config.base_url 是应用地址，不能当认证地址"));
            return;
        }
        ConfigField field = doc.config().get(name);
        if (field == null || !"url".equals(field.type())) {
            errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, path,
                    "config." + name + " 必须是声明过的 type: url 配置项"));
            return;
        }
        names.add(name);
        if (field.defaultValue() instanceof String text && !text.isBlank()) {
            if (SiteAddress.of(text) == null) {
                errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, "config." + name + ".default",
                        "被 auth.origins 引用，默认值必须是 http:// 或 https:// 开头的地址"));
            } else {
                host("config." + name + ".default", text, errors, warnings);
            }
        }
    }

    private static void host(String path, String url, List<ValidationIssue> errors, List<ValidationIssue> warnings) {
        HostNames.Finding finding = HostNames.inspect(url);
        if (finding == null) {
            return;
        }
        if (finding.problem() == HostNames.Problem.SINGLE_LABEL_HTTPS) {
            warnings.add(ConchValidator.issue(ValidationCodes.SINGLE_LABEL_HOST, path, finding.message()));
        } else {
            errors.add(ConchValidator.issue(ValidationCodes.HOSTNAME, path, finding.message()));
        }
    }

    private static void loginSteps(
            List<Step> steps,
            String path,
            Scope scope,
            boolean conditional,
            int depth,
            List<ValidationIssue> warnings
    ) {
        if (steps == null || depth > FlowSteps.MAX_DEPTH) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String typed = path + "[" + i + "]." + step.type();
            if (FlowSteps.isFlow(step)) {
                boolean inner = conditional || FlowSteps.IF.equals(step.type());
                for (String key : FlowSteps.branchKeys(step)) {
                    loginSteps(FlowSteps.branch(step, key), typed + "." + key, scope, inner, depth + 1, warnings);
                }
                continue;
            }
            String key = "request".equals(step.type()) ? addressKey(step.def()) : null;
            if (key == null) {
                continue;
            }
            String url = (String) step.def().get(key);
            Target target = scope.target(url);
            if (target == Target.OTHER) {
                warnings.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, typed + "." + key,
                        "指向 " + SiteAddress.of(url) + "，既不是 base_url 也不在 auth.origins 里，运行时会被拒绝。"
                                + "登录要访问门户或 SSO 时，把它的 scheme://host[:port] 写进 auth.origins"));
            } else if (target == Target.AUTH && !conditional && CREDENTIAL.matcher(String.valueOf(step.def())).find()) {
                warnings.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, typed,
                        "每次登录都向认证地址提交凭证。认证中心上已经登录时会被当成新登录，可能顶掉同一个人的其它会话。"
                                + "建议先请求应用，用 if 判断出现登录页时才提交"));
            }
        }
    }

    private static void actionSteps(
            List<Step> steps, String path, Scope scope, int depth, List<ValidationIssue> errors) {
        if (steps == null || depth > FlowSteps.MAX_DEPTH) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String typed = path + "[" + i + "]." + step.type();
            if (FlowSteps.isFlow(step)) {
                for (String key : FlowSteps.branchKeys(step)) {
                    actionSteps(FlowSteps.branch(step, key), typed + "." + key, scope, depth + 1, errors);
                }
            } else if ("request".equals(step.type())) {
                forbidden(step.def(), typed, scope,
                        "能力步骤只能访问 base_url，认证地址只能在 auth.steps 里访问", errors);
            }
        }
    }

    private static void forbidden(
            Map<String, Object> request, String path, Scope scope, String message, List<ValidationIssue> errors) {
        String key = addressKey(request);
        if (key != null && scope.target((String) request.get(key)) == Target.AUTH) {
            errors.add(ConchValidator.issue(ValidationCodes.AUTH_ORIGIN, path + "." + key, message));
        }
    }

    /** 引擎先看 url，没有才看 path；两个键都能写绝对地址。 */
    private static String addressKey(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        Object url = request.get("url");
        if (url instanceof String text ? !text.isBlank() : url != null) {
            return url instanceof String ? "url" : null;
        }
        return request.get("path") instanceof String ? "path" : null;
    }

    private enum Target { BASE, AUTH, OTHER, UNKNOWN }

    private record Scope(Set<String> declared, Set<String> names, Set<String> bases) {

        /** 相对路径和读不出地址的模板算 UNKNOWN，不在这里判断。 */
        Target target(String url) {
            String name = AuthOrigins.leadingConfigName(url);
            if (name != null) {
                return names.contains(name) ? Target.AUTH : Target.UNKNOWN;
            }
            if (url.contains("{{") && originTemplated(url)) {
                return Target.UNKNOWN;
            }
            String site = SiteAddress.of(url);
            if (site == null) {
                return Target.UNKNOWN;
            }
            if (bases.contains(site)) {
                return Target.BASE;
            }
            return declared.contains(site) ? Target.AUTH : Target.OTHER;
        }

        private static boolean originTemplated(String url) {
            int start = url.indexOf("://");
            if (start < 0) {
                return true;
            }
            int end = url.length();
            for (int i = start + 3; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            return url.substring(0, end).contains("{{");
        }
    }
}
