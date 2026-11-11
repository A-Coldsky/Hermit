package dev.hermit.common.validate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.mcp.McpLimits;
import dev.hermit.common.mcp.ToolSnapshots;
import dev.hermit.common.model.Action;
import dev.hermit.common.model.AliveCheck;
import dev.hermit.common.model.Auth;
import dev.hermit.common.model.ConfigField;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpServerInfo;
import dev.hermit.common.model.McpSnapshot;
import dev.hermit.common.model.McpTool;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.common.model.ParamSpec;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.template.TemplateException;
import dev.hermit.common.template.TemplateRef;
import dev.hermit.common.template.UndefinedVariable;
import dev.hermit.common.version.SemVer;

public final class ConchValidator {

    private static final Set<String> DEFAULT_STEP_TYPES = Set.of(
            "request", "extract", "assert", "set", "script");
    private static final Set<String> BUILTIN_ROOTS = Set.of("input", "response", "now");
    private static final Pattern HEADER_BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final List<String> SECRET_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "otp", "apikey", "api_key",
            "authorization", "cookie");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile(
            "^[a-z][a-z0-9_]{1,63}$");

    private static final TemplateEngine TEMPLATES = new TemplateEngine();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern DESCRIPTION_TAG = Pattern.compile(
            "(?i)<\\s*/?\\s*(important|system|instruction|instructions)\\b");
    private static final Pattern DESCRIPTION_IGNORE = Pattern.compile(
            "(?i)忽略(之前|先前|以前|上面|以上)的指令"
                    + "|ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"
                    + "|disregard\\s+(all\\s+)?(previous|prior)\\s+instructions");
    private static final Pattern DESCRIPTION_URL = Pattern.compile("(?i)\\bhttps?://|\\bwww\\.");
    private static final int SCRIPT_SOURCE_LIMIT = 64 * 1024;
    private static final int SCRIPT_TIMEOUT_MAX = 5000;
    private static final Set<String> SCRIPT_KEYS = Set.of("code", "into", "timeout_ms");

    private ConchValidator() {
    }

    /** Conch 名，规则 {@code ^[a-z][a-z0-9_]{1,63}$}。 */
    public static boolean isConchName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static ValidationResult validate(ConchDoc doc, Context ctx) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(ctx, "ctx");
        ArrayList<ValidationIssue> errors = new ArrayList<>();
        ArrayList<ValidationIssue> warnings = new ArrayList<>();
        if (!NAME.matcher(doc.conch()).matches()) {
            errors.add(issue(ValidationCodes.CONCH_NAME, "conch", "conch 不符合 ^[a-z][a-z0-9_]{1,63}$"));
        }
        boolean mcp = "mcp".equals(doc.driver());
        if (!mcp && !"http".equals(doc.driver())) {
            errors.add(issue(ValidationCodes.DRIVER, "driver", "driver 只允许 http 或 mcp"));
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
        // 解析器仍接受 type: secret，这里拒绝并指向该字段的 type，便于改放到 credentials。
        for (Map.Entry<String, ConfigField> entry : doc.config().entrySet()) {
            ConfigField field = entry.getValue();
            if (field != null && "secret".equals(field.type())) {
                errors.add(issue(
                        ValidationCodes.CONFIG_SECRET,
                        "config." + entry.getKey() + ".type",
                        "秘密要放在 credentials"));
            }
            if (mcp && field != null && "url".equals(field.type())) {
                errors.add(issue(
                        ValidationCodes.MCP_UPSTREAM,
                        "config." + entry.getKey() + ".type",
                        "mcp 包的 config 不能使用 type: url"));
            }
        }
        if (ctx.currentCrabVersion() != null
                && doc.minCrabVersion().compareTo(ctx.currentCrabVersion()) > 0) {
            errors.add(issue(
                    ValidationCodes.MIN_CRAB_VERSION,
                    "min_crab_version",
                    "min_crab_version " + doc.minCrabVersion() + " 高于当前 Crab " + ctx.currentCrabVersion()));
        }
        if (mcp) {
            if (doc.minCrabVersion().compareTo(McpLimits.MIN_CRAB_VERSION) < 0) {
                errors.add(issue(
                        ValidationCodes.MIN_CRAB_VERSION,
                        "min_crab_version",
                        "driver 为 mcp 时 min_crab_version 必须不低于 1.1.0"));
            }
            validateMcp(doc, ctx, errors, warnings);
            return new ValidationResult(errors, warnings);
        }
        rejectMcpOnly(doc, errors);
        LinkedHashSet<String> loginNames = new LinkedHashSet<>();
        Set<String> authInputs = sharedInputFields(doc);
        if (doc.auth() != null) {
            checkSteps(doc.auth().steps(), "auth.steps", ctx, errors);
            checkPlainSteps(doc.auth(), errors);
            checkOrderedSteps(
                    doc.auth().steps(), "auth.steps", doc, new LinkedHashSet<>(), Set.of(), errors, null, authInputs);
            if (doc.auth().steps() != null) {
                for (Step step : doc.auth().steps()) {
                    declare(step, loginNames);
                }
            }
            AliveCheck alive = doc.auth().aliveCheck();
            if (alive != null) {
                checkPlainMap("auth.alive_check.request", alive.request(), false, false, errors);
                checkScalar("auth.alive_check.expect_not_redirect", "expect_not_redirect",
                        alive.expectNotRedirect(), errors);
                checkRefs(
                        "auth.alive_check.request", alive.request(), doc, Set.of(), Set.of(), errors, null, authInputs);
                checkRefs(
                        "auth.alive_check.expect_not_redirect",
                        alive.expectNotRedirect(),
                        doc,
                        Set.of(),
                        Set.of(),
                        errors,
                        null,
                        authInputs);
            }
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            checkAction(entry.getKey(), entry.getValue(), ctx, doc, loginNames, errors, warnings);
        }
        return new ValidationResult(errors, warnings);
    }

    private static void checkAction(
            String name,
            Action action,
            Context ctx,
            ConchDoc doc,
            Set<String> loginNames,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        String path = "actions." + name;
        if (action.tool() != null) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM,
                    path,
                    "只有 driver 为 mcp 时才能写 tool、input_schema、output_schema、upstream_annotations、schema_sha256 或 confirm"));
        }
        if (!NAME.matcher(name).matches()) {
            errors.add(issue(ValidationCodes.ACTION_NAME, path, "能力名不符合 ^[a-z][a-z0-9_]{1,63}$"));
        }
        if (blank(action.title())) {
            errors.add(issue(ValidationCodes.MISSING_TITLE, path + ".title", "缺少 title"));
        }
        if (blank(action.description())) {
            errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, path + ".description", "缺少 description"));
        }
        Set<String> inputFields = action.input().keySet();
        for (Map.Entry<String, ParamSpec> param : action.input().entrySet()) {
            String paramPath = path + ".input." + param.getKey();
            if (blank(param.getValue().description())) {
                errors.add(issue(ValidationCodes.MISSING_DESCRIPTION, paramPath, "参数缺少 description"));
            }
            checkRefs(
                    paramPath + ".default",
                    param.getValue().defaultValue(),
                    doc,
                    Set.of(),
                    Set.of(),
                    errors,
                    null,
                    inputFields);
        }
        checkOutput(path + ".output", action.output(), errors);
        checkCredentialInOutput(path + ".output", action.outputTemplate(), errors);
        checkSteps(action.steps(), path + ".steps", ctx, errors);
        checkPlainStepList(action.steps(), path + ".steps", errors);
        LinkedHashSet<String> declared = new LinkedHashSet<>();
        checkOrderedSteps(
                action.steps(), path + ".steps", doc, declared, loginNames, errors, warnings, inputFields);
        checkRefs(path + ".output", action.output(), doc, declared, loginNames, errors, warnings, inputFields);
        if (action.examples().isEmpty()) {
            errors.add(issue(ValidationCodes.MISSING_EXAMPLES, path + ".examples", "缺少 examples"));
        }
        if (blank(action.notes())) {
            warnings.add(issue(ValidationCodes.MISSING_NOTES, path + ".notes", "缺少 notes"));
        }
        checkRelated(action, doc, path, warnings);
    }

    private static void checkRelated(
            Action action, ConchDoc doc, String path, List<ValidationIssue> warnings) {
        if (action.related() == null) {
            warnings.add(issue(ValidationCodes.MISSING_RELATED, path + ".related", "缺少 related"));
            return;
        }
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
            checkPlainMap(path + "[" + i + "]." + step.type(), step.def(), variableNames, "assert".equals(step.type()), errors);
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
        if (value == null || value instanceof Boolean) {
            return;
        }
        boolean headerBearer = path != null
                && path.contains(".headers.")
                && value instanceof String text
                && HEADER_BEARER.matcher(text).find();
        if (!suspicious(key) && !headerBearer) {
            return;
        }
        if (value instanceof String text && (text.isEmpty() || text.contains("{{"))) {
            return;
        }
        errors.add(issue(
                ValidationCodes.PLAINTEXT_CREDENTIAL,
                path,
                "疑似明文凭证。把它声明成凭证字段，不要写进规则。"));
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

    /**
     * Walks one step list in order. A name produced by extract, set, or script.into
     * can be referenced by a later step in the same list, and by that action's output.
     * Login steps and each action keep separate names. Script source is not a template.
     */
    private static void checkOrderedSteps(
            List<Step> steps,
            String path,
            ConchDoc doc,
            Set<String> declared,
            Set<String> loginNames,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            Set<String> inputFields
    ) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String typed = path + "[" + i + "]." + step.type();
            if ("script".equals(step.type())) {
                checkScriptStep(typed, step, errors);
                for (Map.Entry<String, Object> entry : step.def().entrySet()) {
                    if (!"code".equals(entry.getKey())) {
                        checkRefs(
                                typed + "." + entry.getKey(),
                                entry.getValue(),
                                doc,
                                declared,
                                loginNames,
                                errors,
                                warnings,
                                inputFields);
                    }
                }
            } else {
                checkRefs(typed, step.def(), doc, declared, loginNames, errors, warnings, inputFields);
            }
            declare(step, declared);
        }
    }

    /**
     * Fields an auth template may read. Login runs with the calling action's input, so a field has
     * to be declared on every action.
     */
    private static Set<String> sharedInputFields(ConchDoc doc) {
        LinkedHashSet<String> shared = null;
        for (Action action : doc.actions().values()) {
            if (shared == null) {
                shared = new LinkedHashSet<>(action.input().keySet());
            } else {
                shared.retainAll(action.input().keySet());
            }
        }
        return shared == null ? Set.of() : Set.copyOf(shared);
    }

    private static void declare(Step step, Set<String> declared) {
        if ("extract".equals(step.type()) || "set".equals(step.type())) {
            declared.addAll(step.def().keySet());
            return;
        }
        if ("script".equals(step.type()) && step.def().get("into") instanceof String name && !name.isBlank()) {
            declared.add(name);
        }
    }

    private static void checkRefs(
            String path,
            Object node,
            ConchDoc doc,
            Set<String> declared,
            Set<String> loginNames,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            Set<String> inputFields
    ) {
        if (node == null) {
            return;
        }
        if (node instanceof String text) {
            try {
                for (TemplateRef ref : TEMPLATES.templateRefs(text)) {
                    classifyRef(
                            path,
                            ref.path(),
                            ref.handledByDefault(),
                            doc,
                            declared,
                            loginNames,
                            errors,
                            warnings,
                            inputFields);
                }
            } catch (TemplateException ex) {
                errors.add(issue(ValidationCodes.TEMPLATE_SYNTAX, path, ex.getMessage()));
            }
            return;
        }
        if (node instanceof Step step) {
            String typed = path + "." + step.type();
            if ("script".equals(step.type())) {
                checkScriptStep(typed, step, errors);
                for (Map.Entry<String, Object> entry : step.def().entrySet()) {
                    if (!"code".equals(entry.getKey())) {
                        checkRefs(
                                typed + "." + entry.getKey(),
                                entry.getValue(),
                                doc,
                                declared,
                                loginNames,
                                errors,
                                warnings,
                                inputFields);
                    }
                }
                return;
            }
            checkRefs(typed, step.def(), doc, declared, loginNames, errors, warnings, inputFields);
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                checkRefs(
                        path + "." + entry.getKey(),
                        entry.getValue(),
                        doc,
                        declared,
                        loginNames,
                        errors,
                        warnings,
                        inputFields);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkRefs(
                        path + "[" + i + "]",
                        list.get(i),
                        doc,
                        declared,
                        loginNames,
                        errors,
                        warnings,
                        inputFields);
            }
        }
    }

    private static void classifyRef(
            String path,
            String ref,
            boolean handledByDefault,
            ConchDoc doc,
            Set<String> declared,
            Set<String> loginNames,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings,
            Set<String> inputFields
    ) {
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
        if (config != null) {
            if (!"base_url".equals(config) && !doc.config().containsKey(config)) {
                errors.add(issue(
                        ValidationCodes.UNDECLARED_CONFIG,
                        path,
                        "引用了未声明的配置 " + config));
            }
            return;
        }
        String inputField = head(ref, "input");
        if (inputField != null && inputFields != null) {
            if (!inputFields.contains(inputField)) {
                errors.add(issue(
                        ValidationCodes.UNDECLARED_INPUT,
                        path,
                        "引用了未声明的输入字段 " + inputField));
            }
            return;
        }
        String root = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
        if (BUILTIN_ROOTS.contains(root) || declared.contains(root)) {
            return;
        }
        if (handledByDefault) {
            if (warnings != null && loginNames.contains(root)) {
                warnings.add(issue(
                        ValidationCodes.LOGIN_VARIABLE_EMPTY,
                        path,
                        "能力引用登录步骤变量，运行时为空串；令牌请声明成凭证"));
            }
            return;
        }
        if (loginNames.contains(root)) {
            errors.add(issue(ValidationCodes.UNDECLARED_VARIABLE, path, "能力不能引用登录步骤里的变量。"));
            return;
        }
        errors.add(issue(
                ValidationCodes.UNDECLARED_VARIABLE,
                path,
                UndefinedVariable.message(ref)));
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

    private static void rejectMcpOnly(ConchDoc doc, List<ValidationIssue> errors) {
        if (doc.upstream() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "upstream", "只有 driver 为 mcp 时才能写 upstream"));
        }
        if (doc.tools() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "tools", "只有 driver 为 mcp 时才能写 tools"));
        }
        if (doc.snapshot() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "snapshot", "只有 driver 为 mcp 时才能写 snapshot"));
        }
    }

    private static void validateMcp(
            ConchDoc doc,
            Context ctx,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        if (doc.baseUrl() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "base_url", "driver 为 mcp 时不能写 base_url"));
        }
        if (doc.auth() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "auth", "driver 为 mcp 时不能写 auth"));
        }
        if (!doc.healthCheck().isEmpty()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "health_check", "driver 为 mcp 时不能写 health_check"));
        }
        if (doc.upstream() == null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "upstream", "缺少 upstream"));
        } else {
            checkUpstream(doc, doc.upstream(), errors);
        }
        if (doc.tools() != null) {
            checkPatterns("tools.include", doc.tools().include(), errors);
            checkPatterns("tools.exclude", doc.tools().exclude(), errors);
        }
        if (doc.snapshot() != null) {
            checkSnapshot(doc.snapshot(), errors);
        }
        if (doc.actions().isEmpty()) {
            if (ctx.allowEmptyMcpActions()) {
                warnings.add(issue(
                        ValidationCodes.MCP_NO_ACTIONS,
                        "actions",
                        "mcp 包还没有 action，刷新工具列表后才能调用"));
            } else {
                errors.add(issue(ValidationCodes.MCP_NO_ACTIONS, "actions", "mcp 包必须至少有一个 action"));
            }
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            checkMcpAction(entry.getKey(), entry.getValue(), doc, errors, warnings);
        }
    }

    private static void checkPatterns(String path, List<String> patterns, List<ValidationIssue> errors) {
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i) == null || patterns.get(i).isBlank()) {
                errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + "[" + i + "]", "过滤模式不能为空"));
            }
        }
    }

    private static void checkUpstream(ConchDoc doc, McpUpstream upstream, List<ValidationIssue> errors) {
        McpAddresses.ParseResult url = checkAddress("upstream.url", "上游地址", upstream.url(), errors);
        if (!McpLimits.TRANSPORT.equals(upstream.transport())) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM, "upstream.transport", "transport 只接受 streamable_http"));
        }
        if (upstream.timeoutMs() != null && upstream.timeoutMs() <= 0) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "upstream.timeout_ms", "timeout_ms 必须是正整数"));
        }
        if (upstream.allowedHosts().isEmpty()) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM,
                    "upstream.allowed_hosts",
                    "allowed_hosts 为空，且无法从 upstream.url 得到主机"));
        }
        for (int i = 0; i < upstream.allowedHosts().size(); i++) {
            if (!McpAddresses.validPattern(upstream.allowedHosts().get(i))) {
                errors.add(issue(
                        ValidationCodes.MCP_UPSTREAM,
                        "upstream.allowed_hosts[" + i + "]",
                        "allowed_hosts 只接受主机名、IPv6 字面量或 *.后缀"));
            }
        }
        if (url.ok() && !McpAddresses.hostAllowed(url.url().host(), upstream.allowedHosts())) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "upstream.url", "主机不在 allowed_hosts 中"));
        }
        McpAuth auth = upstream.auth();
        if (auth == null || (!auth.headers() && !auth.oauth())) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM, "upstream.auth.type", "auth.type 只允许 headers 或 oauth"));
        } else if (auth.oauth()) {
            if (auth.clientId() == null || auth.clientId().isBlank()) {
                errors.add(issue(
                        ValidationCodes.MCP_UPSTREAM, "upstream.auth.client_id", "oauth 必须写 client_id"));
            }
            for (int i = 0; i < auth.scopes().size(); i++) {
                if (auth.scopes().get(i) == null || auth.scopes().get(i).isBlank()) {
                    errors.add(issue(
                            ValidationCodes.MCP_UPSTREAM,
                            "upstream.auth.scopes[" + i + "]",
                            "scope 不能为空"));
                }
            }
            checkEndpoint(
                    "upstream.auth.authorization_endpoint",
                    "授权端点",
                    auth.authorizationEndpoint(),
                    upstream.allowedHosts(),
                    url,
                    errors);
            checkEndpoint(
                    "upstream.auth.token_endpoint",
                    "令牌端点",
                    auth.tokenEndpoint(),
                    upstream.allowedHosts(),
                    url,
                    errors);
        }
        checkHeaders(doc, upstream, errors);
    }

    private static void checkEndpoint(
            String path,
            String label,
            String text,
            List<String> allowedHosts,
            McpAddresses.ParseResult upstream,
            List<ValidationIssue> errors
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        McpAddresses.ParseResult parsed = checkAddress(path, label, text, errors);
        if (!parsed.ok()) {
            return;
        }
        if (!McpAddresses.hostAllowed(parsed.url().host(), allowedHosts)) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, label + "的主机不在 allowed_hosts 中"));
        }
        if ("http".equals(parsed.url().scheme())
                && (upstream == null || !upstream.ok()
                || !"http".equals(upstream.url().scheme())
                || !McpAddresses.sameOrigin(upstream.url(), parsed.url()))) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM, path, label + "若使用 http，必须与 upstream.url 同一源"));
        }
    }

    private static McpAddresses.ParseResult checkAddress(
            String path,
            String label,
            String text,
            List<ValidationIssue> errors
    ) {
        if (text == null || text.isBlank()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, "缺少" + label));
            return new McpAddresses.ParseResult(null, McpAddresses.Problem.HOST);
        }
        McpAddresses.ParseResult parsed = McpAddresses.parse(text);
        if (!parsed.ok()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, label + addressMessage(parsed.problem())));
        }
        return parsed;
    }

    private static String addressMessage(McpAddresses.Problem problem) {
        return switch (problem) {
            case TEMPLATE -> "不能使用模板";
            case SYNTAX -> "不是合法的 URL";
            case SCHEME -> "只允许 http 或 https";
            case USERINFO -> "不能带用户信息";
            case FRAGMENT -> "不能带片段";
            case HOST -> "缺少主机";
        };
    }

    private static void checkHeaders(ConchDoc doc, McpUpstream upstream, List<ValidationIssue> errors) {
        boolean oauth = upstream.auth() != null && upstream.auth().oauth();
        for (Map.Entry<String, String> entry : upstream.headers().entrySet()) {
            String path = "upstream.headers." + entry.getKey();
            String key = McpLimits.headerKey(entry.getKey());
            if (oauth && "authorization".equals(key)) {
                errors.add(issue(ValidationCodes.MCP_HEADER, path, "oauth 不能设置 Authorization"));
            } else if (McpLimits.FORBIDDEN_HEADERS.contains(key)) {
                errors.add(issue(ValidationCodes.MCP_HEADER, path, "不能设置保留头 " + entry.getKey()));
            }
            checkHeaderRefs(path, entry.getValue(), doc, errors);
            checkScalar(path, entry.getKey(), entry.getValue(), errors);
        }
    }

    private static void checkHeaderRefs(String path, String value, ConchDoc doc, List<ValidationIssue> errors) {
        if (value == null) {
            return;
        }
        try {
            for (String ref : TEMPLATES.referencedPaths(value)) {
                if ("input".equals(ref) || ref.startsWith("input.") || ref.startsWith("input[")) {
                    errors.add(issue(ValidationCodes.MCP_HEADER, path, "请求头不能引用 input"));
                    continue;
                }
                String credential = head(ref, "credential");
                if (credential != null) {
                    if (doc.credentials() == null || !doc.credentials().containsKey(credential)) {
                        errors.add(issue(
                                ValidationCodes.UNDECLARED_CREDENTIAL, path, "引用了未声明的凭证 " + credential));
                    }
                    continue;
                }
                String config = head(ref, "config");
                if (config != null) {
                    if (!doc.config().containsKey(config)) {
                        errors.add(issue(
                                ValidationCodes.UNDECLARED_CONFIG, path, "引用了未声明的配置 " + config));
                    }
                    continue;
                }
                errors.add(issue(ValidationCodes.MCP_HEADER, path, "请求头只能引用 credential 或 config"));
            }
        } catch (TemplateException ex) {
            errors.add(issue(ValidationCodes.TEMPLATE_SYNTAX, path, ex.getMessage()));
        }
    }

    private static void checkSnapshot(McpSnapshot snapshot, List<ValidationIssue> errors) {
        if (snapshot.fetchedAt() == null || snapshot.fetchedAt().isBlank()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, "snapshot.fetched_at", "缺少 fetched_at"));
        } else {
            try {
                Instant.parse(snapshot.fetchedAt());
            } catch (DateTimeParseException ex) {
                errors.add(issue(
                        ValidationCodes.MCP_UPSTREAM, "snapshot.fetched_at", "fetched_at 必须是 UTC 时间"));
            }
        }
        if (snapshot.protocolVersion() == null || snapshot.protocolVersion().isBlank()) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM, "snapshot.protocol_version", "缺少 protocol_version"));
        }
        if (snapshot.toolsSha256() == null || !SHA256_HEX.matcher(snapshot.toolsSha256()).matches()) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM,
                    "snapshot.tools_sha256",
                    "tools_sha256 必须是 64 位小写十六进制"));
        }
        McpServerInfo server = snapshot.server();
        if (server != null) {
            if (server.name() == null || server.name().isBlank()) {
                errors.add(issue(ValidationCodes.MCP_UPSTREAM, "snapshot.server.name", "缺少 server.name"));
            }
            if (server.version() == null || server.version().isBlank()) {
                errors.add(issue(ValidationCodes.MCP_UPSTREAM, "snapshot.server.version", "缺少 server.version"));
            }
        }
    }

    private static void checkMcpAction(
            String name,
            Action action,
            ConchDoc doc,
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
        } else {
            checkDescription(path + ".description", action.description(), warnings);
        }
        if (action.method() != null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".method", "driver 为 mcp 时不能写 method"));
        }
        if (!action.input().isEmpty()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".input", "driver 为 mcp 时不能写 input"));
        }
        if (!action.output().isEmpty()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".output", "driver 为 mcp 时不能写 output"));
        }
        if (!action.steps().isEmpty()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".steps", "driver 为 mcp 时不能写 steps"));
        }
        McpTool tool = action.tool();
        if (tool == null || tool.name() == null || tool.name().isBlank() || !validToolName(tool.name())) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".tool", "缺少或无效的上游工具名 tool"));
        }
        if (tool == null || tool.inputSchema() == null) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path + ".input_schema", "缺少 input_schema"));
        } else {
            checkSchema(path + ".input_schema", "input_schema", tool.inputSchema(), errors);
        }
        if (tool != null && tool.outputSchema() != null) {
            checkSchema(path + ".output_schema", "output_schema", tool.outputSchema(), errors);
        }
        if (tool != null) {
            checkHint(path + ".upstream_annotations", tool, errors);
            checkDeclaredSchemaHash(path + ".schema_sha256", tool, errors);
        }
        if (action.examples().isEmpty()) {
            warnings.add(issue(ValidationCodes.MISSING_EXAMPLES, path + ".examples", "缺少 examples"));
        }
        if (blank(action.notes())) {
            warnings.add(issue(ValidationCodes.MISSING_NOTES, path + ".notes", "缺少 notes"));
        }
        checkRelated(action, doc, path, warnings);
    }

    private static boolean validToolName(String name) {
        if (name == null || name.length() > 256) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            if (codePoint < 0x20 || codePoint == 0x7F || Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static void checkHint(String path, McpTool tool, List<ValidationIssue> errors) {
        for (String key : List.of("readOnlyHint", "destructiveHint", "idempotentHint")) {
            if (tool.upstreamAnnotations().containsKey(key)
                    && !(tool.upstreamAnnotations().get(key) instanceof Boolean)) {
                errors.add(issue(
                        ValidationCodes.MCP_UPSTREAM, path + "." + key, key + " 必须是布尔值"));
            }
        }
    }

    private static void checkDeclaredSchemaHash(String path, McpTool tool, List<ValidationIssue> errors) {
        if (tool.schemaSha256() == null) {
            return;
        }
        if (!SHA256_HEX.matcher(tool.schemaSha256()).matches()) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, "schema_sha256 必须是 64 位小写十六进制"));
            return;
        }
        if (tool.inputSchema() == null) {
            return;
        }
        String computed = ToolSnapshots.schemaSha256(tool.inputSchema(), tool.outputSchema());
        if (!computed.equals(tool.schemaSha256())) {
            errors.add(issue(
                    ValidationCodes.MCP_UPSTREAM, path, "schema_sha256 与 input_schema、output_schema 不一致"));
        }
    }

    private static void checkSchema(
            String path,
            String label,
            Map<String, Object> schema,
            List<ValidationIssue> errors
    ) {
        if (depth(schema) > McpLimits.SCHEMA_MAX_DEPTH) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, label + " 深度超过 32"));
        }
        try {
            if (JSON.writeValueAsBytes(schema).length > McpLimits.SCHEMA_MAX_BYTES) {
                errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, label + " 超过 64 KiB"));
            }
        } catch (JsonProcessingException ex) {
            errors.add(issue(ValidationCodes.MCP_UPSTREAM, path, label + " 无法序列化"));
        }
        walkRefs(path, schema, errors);
    }

    private static void walkRefs(String path, Object node, List<ValidationIssue> errors) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String child = path + "." + key;
                if ("$ref".equals(key) && !localRef(entry.getValue())) {
                    errors.add(issue(
                            ValidationCodes.MCP_SCHEMA_REF, child, "schema 只允许本地 $ref（#/…）"));
                }
                walkRefs(child, entry.getValue(), errors);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walkRefs(path + "[" + i + "]", list.get(i), errors);
            }
        }
    }

    private static boolean localRef(Object value) {
        return value instanceof String ref && ("#".equals(ref) || ref.startsWith("#/"));
    }

    private static int depth(Object node) {
        if (node instanceof Map<?, ?> map) {
            int max = 1;
            for (Object child : map.values()) {
                max = Math.max(max, 1 + depth(child));
            }
            return max;
        }
        if (node instanceof List<?> list) {
            int max = 1;
            for (Object child : list) {
                max = Math.max(max, 1 + depth(child));
            }
            return max;
        }
        return 0;
    }

    private static void checkDescription(String path, String description, List<ValidationIssue> warnings) {
        if (description.getBytes(StandardCharsets.UTF_8).length > McpLimits.DESCRIPTION_MAX_BYTES) {
            warnings.add(issue(ValidationCodes.MCP_DESCRIPTION, path, "描述超过 2 KiB"));
        }
        if (hiddenText(description)) {
            warnings.add(issue(ValidationCodes.MCP_DESCRIPTION, path, "描述含有控制字符、零宽字符或 ANSI 转义"));
        }
        if (DESCRIPTION_TAG.matcher(description).find()) {
            warnings.add(issue(ValidationCodes.MCP_DESCRIPTION, path, "描述含有疑似指令标签"));
        }
        if (DESCRIPTION_IGNORE.matcher(description).find()) {
            warnings.add(issue(ValidationCodes.MCP_DESCRIPTION, path, "描述含有「忽略之前的指令」一类句子"));
        }
        if (DESCRIPTION_URL.matcher(description).find()) {
            warnings.add(issue(ValidationCodes.MCP_DESCRIPTION, path, "描述含有 URL"));
        }
    }

    private static boolean hiddenText(String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint == 0x7F || (codePoint < 0x20 && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
                return true;
            }
            switch (codePoint) {
                case 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064,
                        0xFEFF, 0x180E, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069:
                    return true;
                default:
                    break;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public record Context(
            SemVer currentCrabVersion,
            Set<String> supportedStepTypes,
            boolean allowEmptyMcpActions
    ) {

        public Context {
            supportedStepTypes = supportedStepTypes == null
                    ? DEFAULT_STEP_TYPES
                    : Set.copyOf(supportedStepTypes);
        }

        public Context(SemVer currentCrabVersion, Set<String> supportedStepTypes) {
            this(currentCrabVersion, supportedStepTypes, false);
        }

        public static Context of(SemVer currentCrabVersion) {
            return new Context(currentCrabVersion, DEFAULT_STEP_TYPES, false);
        }

        public static Context of(SemVer currentCrabVersion, Set<String> supportedStepTypes) {
            return new Context(currentCrabVersion, supportedStepTypes, false);
        }

        /** 调试开关打开时，本地导入可以把空 actions 当成警告。Reef 上传不使用这个上下文。 */
        public static Context allowingEmptyMcpActions(SemVer currentCrabVersion, Set<String> supportedStepTypes) {
            return new Context(currentCrabVersion, supportedStepTypes, true);
        }
    }
}
