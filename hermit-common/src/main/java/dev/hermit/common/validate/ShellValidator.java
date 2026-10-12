package dev.hermit.common.validate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.common.version.SemVer;

public final class ShellValidator {

    private static final Set<String> DEFAULT_STEP_TYPES = Set.of(
            "request", "extract", "assert", "set", "script");
    private static final List<String> SECRET_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "otp", "apikey", "api_key",
            "authorization", "cookie");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile(
            "^[a-z][a-z0-9_]{1,63}$");

    private static final TemplateEngine TEMPLATES = new TemplateEngine();
    private static final int SCRIPT_SOURCE_LIMIT = 64 * 1024;
    private static final int SCRIPT_TIMEOUT_MAX = 5000;
    private static final Set<String> SCRIPT_KEYS = Set.of("code", "into", "timeout_ms");

    private ShellValidator() {
    }

    /** Shell 名，规则 {@code ^[a-z][a-z0-9_]{1,63}$}。 */
    public static boolean isShellName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static ValidationResult validate(ShellDoc doc, Context ctx) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(ctx, "ctx");
        ArrayList<ValidationIssue> errors = new ArrayList<>();
        ArrayList<ValidationIssue> warnings = new ArrayList<>();
        if (!NAME.matcher(doc.shell()).matches()) {
            errors.add(issue(ValidationCodes.SHELL_NAME, "shell", "shell 不符合 ^[a-z][a-z0-9_]{1,63}$"));
        }
        if (!"http".equals(doc.driver())) {
            errors.add(issue(ValidationCodes.DRIVER, "driver", "driver 只允许 http"));
        }
        if (blank(doc.title())) {
            errors.add(issue(ValidationCodes.MISSING_TITLE, "title", "缺少 title"));
        }
        if (blank(doc.description())) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, "description", "缺少 description"));
        }
        if (doc.credentials() == null) {
            errors.add(issue(ValidationCodes.MISSING_CREDENTIALS, "credentials", "缺少 credentials"));
        }
        if (ctx.currentCrabVersion() != null
                && doc.minCrabVersion().compareTo(ctx.currentCrabVersion()) > 0) {
            errors.add(issue(
                    ValidationCodes.MIN_CRAB_VERSION,
                    "min_crab_version",
                    "min_crab_version " + doc.minCrabVersion() + " 高于当前 Crab " + ctx.currentCrabVersion()));
        }
        if (doc.auth() != null) {
            checkSteps(doc.auth().steps(), "auth.steps", ctx, errors);
            checkPlainSteps(doc.auth(), errors);
            checkRefs("auth.steps", doc.auth().steps(), doc, errors);
            AliveCheck alive = doc.auth().aliveCheck();
            if (alive != null) {
                checkPlainMap("auth.alive_check.request", alive.request(), false, false, errors);
                checkScalar("auth.alive_check.expect_not_redirect", "expect_not_redirect",
                        alive.expectNotRedirect(), errors);
                checkRefs("auth.alive_check.request", alive.request(), doc, errors);
                checkRefs("auth.alive_check.expect_not_redirect", alive.expectNotRedirect(), doc, errors);
            }
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            checkAction(entry.getKey(), entry.getValue(), ctx, doc, errors, warnings);
        }
        return new ValidationResult(errors, warnings);
    }

    private static void checkAction(
            String name,
            Action action,
            Context ctx,
            ShellDoc doc,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        String path = "actions." + name;
        if (!NAME.matcher(name).matches()) {
            errors.add(issue(ValidationCodes.ACTION_NAME, path, "能力名不符合 ^[a-z][a-z0-9_]{1,63}$"));
        }
        if (blank(action.title())) {
            errors.add(issue(ValidationCodes.MISSING_TITLE, path + ".title", "缺少 title"));
        }
        if (blank(action.description())) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path + ".description", "缺少 description"));
        }
        for (Map.Entry<String, ParamSpec> param : action.input().entrySet()) {
            String paramPath = path + ".input." + param.getKey();
            if (blank(param.getValue().description())) {
                errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, paramPath, "参数缺少 description"));
            }
            checkRefs(paramPath + ".default", param.getValue().defaultValue(), doc, errors);
        }
        checkOutput(path + ".output", action.output(), errors);
        checkRefs(path + ".output", action.output(), doc, errors);
        checkCredentialInOutput(path + ".output", action.outputTemplate(), errors);
        checkSteps(action.steps(), path + ".steps", ctx, errors);
        checkPlainStepList(action.steps(), path + ".steps", errors);
        checkRefs(path + ".steps", action.steps(), doc, errors);
        if (action.examples().isEmpty()) {
            errors.add(issue(ValidationCodes.MISSING_EXAMPLES, path + ".examples", "缺少 examples"));
        }
        if (blank(action.notes())) {
            warnings.add(issue(ValidationCodes.MISSING_NOTES, path + ".notes", "缺少 notes"));
        }
        if (action.related().isEmpty()) {
            warnings.add(issue(ValidationCodes.MISSING_RELATED, path + ".related", "缺少 related"));
        } else {
            for (int i = 0; i < action.related().size(); i++) {
                String related = action.related().get(i);
                if (!doc.actions().containsKey(related)) {
                    warnings.add(issue(
                            ValidationCodes.UNKNOWN_RELATED,
                            path + ".related[" + i + "]",
                            "related 指向不存在的能力 " + related));
                }
            }
        }
    }

    private static void checkOutput(String path, Map<String, Object> output, List<ValidationIssue> errors) {
        if (output == null || output.isEmpty()) {
            errors.add(issue(ValidationCodes.MISSING_OUTPUT, path, "缺少 output"));
            return;
        }
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            checkOutputField(path + "." + entry.getKey(), entry.getValue(), errors);
        }
    }

    private static void checkOutputField(String path, Object value, List<ValidationIssue> errors) {
        if (value instanceof String) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path, "映射写法缺少返回字段说明"));
            return;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path, "缺少返回字段说明"));
            return;
        }
        Map<String, Object> map = cast(raw);
        if (map.isEmpty()) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path, "缺少返回字段说明"));
            return;
        }
        if (!isDescription(map)) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                checkOutputField(path + "." + entry.getKey(), entry.getValue(), errors);
            }
            return;
        }
        if (!(map.get("description") instanceof String description) || description.isBlank()) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path, "返回字段缺少 description"));
        }
        if (map.containsKey("items")) {
            checkItems(path + ".items", map.get("items"), errors);
        }
    }

    private static void checkItems(String path, Object items, List<ValidationIssue> errors) {
        if (!(items instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> map = cast(raw);
        if (isDescription(map)) {
            checkOutputField(path, map, errors);
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            checkOutputField(path + "." + entry.getKey(), entry.getValue(), errors);
        }
    }

    private static boolean isDescription(Map<String, Object> map) {
        return map.get("type") instanceof String;
    }

    private static void checkSteps(List<Step> steps, String path, Context ctx, List<ValidationIssue> errors) {
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (!ctx.supportedStepTypes().contains(step.type())) {
                errors.add(issue(
                        ValidationCodes.UNSUPPORTED_STEP,
                        path + "[" + i + "]",
                        "不支持的步骤类型 " + step.type()));
            }
        }
    }

    private static void checkPlainSteps(Auth auth, List<ValidationIssue> errors) {
        checkPlainStepList(auth.steps(), "auth.steps", errors);
    }

    private static void checkPlainStepList(List<Step> steps, String path, List<ValidationIssue> errors) {
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            boolean variableNames = "extract".equals(step.type());
            checkPlainMap(path + "[" + i + "]", step.def(), variableNames, "assert".equals(step.type()), errors);
        }
    }

    private static void checkPlainMap(
            String path,
            Map<String, Object> map,
            boolean keysAreVariableNames,
            boolean assertStep,
            List<ValidationIssue> errors
    ) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String childPath = path + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                checkPlainMap(childPath, cast(nested), false, assertStep, errors);
            } else if (value instanceof List<?> list) {
                checkPlainList(childPath, entry.getKey(), list, keysAreVariableNames, assertStep, errors);
            } else if (!keysAreVariableNames && !exemptAssertKey(assertStep, entry.getKey())) {
                checkScalar(childPath, entry.getKey(), value, errors);
            }
        }
    }

    private static void checkPlainList(
            String path,
            String parentKey,
            List<?> list,
            boolean parentKeySkipped,
            boolean assertStep,
            List<ValidationIssue> errors
    ) {
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            String childPath = path + "[" + i + "]";
            if (value instanceof Map<?, ?> nested) {
                checkPlainMap(childPath, cast(nested), false, assertStep, errors);
            } else if (value instanceof List<?> nested) {
                checkPlainList(childPath, parentKey, nested, parentKeySkipped, assertStep, errors);
            } else if (!parentKeySkipped && !exemptAssertKey(assertStep, parentKey)) {
                checkScalar(childPath, parentKey, value, errors);
            }
        }
    }

    private static void checkScalar(String path, String key, Object value, List<ValidationIssue> errors) {
        if (value == null || value instanceof Boolean || !suspicious(key)) {
            return;
        }
        if (value instanceof String text && (text.isEmpty() || text.contains("{{"))) {
            return;
        }
        errors.add(issue(ValidationCodes.PLAINTEXT_CREDENTIAL, path, "疑似明文凭证"));
    }

    private static final Set<String> ASSERT_NAME_KEYS = Set.of("cookie_exists", "header_exists");

    /** assert 里这两个键的值是要检查的名字，不是秘密。其他位置的 cookie_* / authorization_* 仍算敏感键。 */
    private static boolean exemptAssertKey(boolean assertStep, String key) {
        return assertStep && key != null && ASSERT_NAME_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private static boolean suspicious(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String needle : SECRET_KEYS) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void checkCredentialInOutput(String path, Object node, List<ValidationIssue> errors) {
        if (node == null) {
            return;
        }
        if (node instanceof String text) {
            try {
                for (String ref : TEMPLATES.referencedPaths(text)) {
                    if (ref.startsWith("credential.")) {
                        errors.add(issue(ValidationCodes.CREDENTIAL_IN_OUTPUT, path, "返回值不得引用凭证"));
                    }
                }
            } catch (TemplateException ex) {
                // 语法错误已由原始 output 的引用扫描记为 TEMPLATE_SYNTAX。
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                checkCredentialInOutput(path + "." + entry.getKey(), entry.getValue(), errors);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkCredentialInOutput(path + "[" + i + "]", list.get(i), errors);
            }
        }
    }

    private static void checkScriptStep(String path, Step step, List<ValidationIssue> errors) {
        Map<String, Object> def = step.def();
        for (String key : def.keySet()) {
            if (!SCRIPT_KEYS.contains(key)) {
                errors.add(issue(ValidationCodes.SCRIPT_STEP, path + "." + key, "未知的 script 字段"));
            }
        }
        if (!def.containsKey("code")) {
            errors.add(issue(ValidationCodes.SCRIPT_STEP, path + ".code", "script 缺少 code"));
        } else if (!(def.get("code") instanceof String text)) {
            errors.add(issue(ValidationCodes.SCRIPT_STEP, path + ".code", "script code 必须是字符串"));
        } else if (text.getBytes(StandardCharsets.UTF_8).length > SCRIPT_SOURCE_LIMIT) {
            errors.add(issue(ValidationCodes.SCRIPT_STEP, path + ".code", "脚本源码超过 64KB"));
        }
        if (def.containsKey("into") && (!(def.get("into") instanceof String name) || name.isBlank())) {
            errors.add(issue(ValidationCodes.SCRIPT_STEP, path + ".into", "script into 无效"));
        }
        if (def.containsKey("timeout_ms") && !validTimeout(def.get("timeout_ms"))) {
            errors.add(issue(
                    ValidationCodes.SCRIPT_STEP,
                    path + ".timeout_ms",
                    "script timeout_ms 必须是 1 到 5000 的整数"));
        }
    }

    private static boolean validTimeout(Object value) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double raw = number.doubleValue();
        return Double.isFinite(raw) && raw == Math.rint(raw) && raw >= 1 && raw <= SCRIPT_TIMEOUT_MAX;
    }

    private static void checkRefs(String path, Object node, ShellDoc doc, List<ValidationIssue> errors) {
        if (node == null) {
            return;
        }
        if (node instanceof String text) {
            try {
                for (String ref : TEMPLATES.referencedPaths(text)) {
                    classifyRef(path, ref, doc, errors);
                }
            } catch (TemplateException ex) {
                errors.add(issue(ValidationCodes.TEMPLATE_SYNTAX, path, ex.getMessage()));
            }
            return;
        }
        if (node instanceof Step step) {
            if ("script".equals(step.type())) {
                checkScriptStep(path, step, errors);
                for (Map.Entry<String, Object> entry : step.def().entrySet()) {
                    if (!"code".equals(entry.getKey())) {
                        checkRefs(path + "." + entry.getKey(), entry.getValue(), doc, errors);
                    }
                }
                return;
            }
            checkRefs(path, step.def(), doc, errors);
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                checkRefs(path + "." + entry.getKey(), entry.getValue(), doc, errors);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkRefs(path + "[" + i + "]", list.get(i), doc, errors);
            }
        }
    }

    private static void classifyRef(String path, String ref, ShellDoc doc, List<ValidationIssue> errors) {
        String credential = head(ref, "credential");
        if (credential != null) {
            if (doc.credentials() == null || !doc.credentials().containsKey(credential)) {
                errors.add(issue(
                        ValidationCodes.UNDECLARED_CREDENTIAL,
                        path,
                        "引用了未声明的凭证 " + credential));
            }
            return;
        }
        String config = head(ref, "config");
        if (config != null && !"base_url".equals(config) && !doc.config().containsKey(config)) {
            errors.add(issue(
                    ValidationCodes.UNDECLARED_CONFIG,
                    path,
                    "引用了未声明的配置 " + config));
        }
    }

    private static String head(String ref, String root) {
        String prefix = root + ".";
        if (!ref.startsWith(prefix)) {
            return null;
        }
        String rest = ref.substring(prefix.length());
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '.' || c == '[') {
                cut = i;
                break;
            }
        }
        if (cut == 0) {
            return null;
        }
        return rest.substring(0, cut);
    }

    private static ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    public record Context(SemVer currentCrabVersion, Set<String> supportedStepTypes) {

        public Context {
            supportedStepTypes = supportedStepTypes == null
                    ? DEFAULT_STEP_TYPES
                    : Set.copyOf(supportedStepTypes);
        }

        public static Context of(SemVer currentCrabVersion) {
            return new Context(currentCrabVersion, DEFAULT_STEP_TYPES);
        }

        public static Context of(SemVer currentCrabVersion, Set<String> supportedStepTypes) {
            return new Context(currentCrabVersion, supportedStepTypes);
        }
    }
}
