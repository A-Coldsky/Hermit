package dev.hermit.common.validate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.hermit.common.model.Action;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.FlowSteps;
import dev.hermit.common.model.Step;
import dev.hermit.common.version.SemVer;

/**
 * 1.14.0 起的脚本能力校验：
 * <ul>
 *   <li>{@code hermit} 加解密与 GBK 编解码辅助函数（{@code md5}、{@code sha1}、{@code sha256}、{@code sha512}、
 *       {@code hmacSha1}、{@code hmacSha256}、{@code aesEncrypt}、{@code aesDecrypt}、{@code rsaEncrypt}、
 *       {@code gbk}、{@code fromGbk}）要求 {@code min_crab_version >= 1.14.0}；</li>
 *   <li>{@code script}（声明 {@code credential: true}）读取 {@code vars.login} 或业务包里的 {@code vars.auth}
 *       要求 {@code min_crab_version >= 1.14.0}，能力步骤中点名的 {@code vars.login.名字} 须已在登录步骤或
 *       {@code auth.cookies} 中声明；</li>
 *   <li>未声明 {@code credential: true} 的普通 {@code script} 访问 {@code vars.login.*} 或业务包的
 *       {@code vars.auth.*} 时给出警告提示（运行时该视图为 {@code undefined}）。</li>
 * </ul>
 */
public final class ScriptCryptoChecks {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.14.0");

    public static final Set<String> HERMIT_114_FUNCTIONS = Set.of(
            "md5",
            "sha1",
            "sha256",
            "sha512",
            "hmacSha1",
            "hmacSha256",
            "aesEncrypt",
            "aesDecrypt",
            "rsaEncrypt",
            "gbk",
            "fromGbk");

    public static final String CRYPTO_VERSION =
            "使用 hermit 1.14 加解密或 GBK 编解码函数时 min_crab_version 必须不低于 1.14.0";
    public static final String SECRET_VARS_VERSION =
            "在 script (credential: true) 中读取 vars.login 或 vars.auth 时 min_crab_version 必须不低于 1.14.0";
    public static final String PLAIN_SCRIPT_SECRET_WARNING =
            "普通 script 中 vars.login 与授权器 vars.auth 为 undefined；如需读取登录或授权器变量，请为该 script 声明 credential: true 且 min_crab_version >= 1.14.0";

    private ScriptCryptoChecks() {
    }

    public static void check(
            ConchDoc doc,
            Set<String> loginVars,
            AtomicBoolean usesLogin,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        if ("mcp".equals(doc.driver())) {
            return;
        }
        boolean usesAuthPkg = AuthenticatorChecks.uses(doc);
        AtomicBoolean usesHermit114 = new AtomicBoolean(false);
        AtomicBoolean usesSecretVars114 = new AtomicBoolean(false);

        for (String libSource : doc.scripts().values()) {
            if (libSource != null && scanHermit114(libSource)) {
                usesHermit114.set(true);
            }
        }
        if (doc.auth() != null) {
            walkSteps(
                    doc.auth().steps(),
                    "auth.steps",
                    0,
                    true,
                    usesAuthPkg,
                    doc.scripts(),
                    loginVars,
                    usesLogin,
                    usesHermit114,
                    usesSecretVars114,
                    errors,
                    warnings);
            walkSteps(
                    doc.auth().exchange(),
                    "auth.exchange",
                    0,
                    true,
                    usesAuthPkg,
                    doc.scripts(),
                    loginVars,
                    usesLogin,
                    usesHermit114,
                    usesSecretVars114,
                    errors,
                    warnings);
        }
        for (Map.Entry<String, Action> entry : doc.actions().entrySet()) {
            Action action = entry.getValue();
            if (action == null) {
                continue;
            }
            walkSteps(
                    action.steps(),
                    "actions." + entry.getKey() + ".steps",
                    0,
                    false,
                    usesAuthPkg,
                    doc.scripts(),
                    loginVars,
                    usesLogin,
                    usesHermit114,
                    usesSecretVars114,
                    errors,
                    warnings);
        }

        if (doc.minCrabVersion().compareTo(MIN_CRAB_VERSION) < 0) {
            if (usesHermit114.get()) {
                errors.add(ConchValidator.issue(ValidationCodes.MIN_CRAB_VERSION, "min_crab_version", CRYPTO_VERSION));
            }
            if (usesSecretVars114.get()) {
                errors.add(ConchValidator.issue(
                        ValidationCodes.MIN_CRAB_VERSION, "min_crab_version", SECRET_VARS_VERSION));
            }
        }
    }

    /**
     * 收集单个 {@code credential: true} 的 {@code script} 步骤显式引用的 {@code vars.login.名字}，
     * 供运行时在执行能力步骤前按需触发登录。
     */
    public static void collectCredentialScriptLoginRefs(
            Step step,
            Map<String, String> scripts,
            Set<String> loginNamesOut
    ) {
        if (step == null || !"script".equals(step.type())) {
            return;
        }
        Map<String, Object> def = step.def();
        if (def == null || !Boolean.TRUE.equals(def.get("credential"))) {
            return;
        }
        if (def.get("code") instanceof String code) {
            scanVarsFields(code, "login", loginNamesOut);
        }
        if (scripts != null && def.get("use") instanceof List<?> libs) {
            for (Object item : libs) {
                if (item instanceof String name) {
                    String lib = scripts.get(name);
                    if (lib != null) {
                        scanVarsFields(lib, "login", loginNamesOut);
                    }
                }
            }
        }
    }

    /** 跳过 JS 注释与静态字符串字面量（支持模板字符串插值 {@code ${...}}），提取 {@code vars.<root>.x} 与下标引用。 */
    public static void scanVarsFields(String code, String root, Set<String> fieldsOut) {
        if (code == null || code.isEmpty()) {
            return;
        }
        ScanResult result = new ScanResult();
        scanSource(code, 0, code.length(), false, root, result);
        for (String field : result.fields) {
            if (isValidField(root, field)) {
                fieldsOut.add(field);
            }
        }
    }

    private static void walkSteps(
            List<Step> steps,
            String basePath,
            int depth,
            boolean inAuthSteps,
            boolean usesAuthPkg,
            Map<String, String> scripts,
            Set<String> loginVars,
            AtomicBoolean usesLogin,
            AtomicBoolean usesHermit114,
            AtomicBoolean usesSecretVars114,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String stepPath = basePath + "[" + i + "]." + step.type();
            if ("script".equals(step.type())) {
                inspectScriptStep(
                        stepPath,
                        step.def(),
                        inAuthSteps,
                        usesAuthPkg,
                        scripts,
                        loginVars,
                        usesLogin,
                        usesHermit114,
                        usesSecretVars114,
                        errors,
                        warnings);
            } else if (FlowSteps.isFlow(step) && depth < FlowSteps.MAX_DEPTH) {
                for (String key : FlowSteps.branchKeys(step)) {
                    walkSteps(
                            FlowSteps.branch(step, key),
                            stepPath + "." + key,
                            depth + 1,
                            inAuthSteps,
                            usesAuthPkg,
                            scripts,
                            loginVars,
                            usesLogin,
                            usesHermit114,
                            usesSecretVars114,
                            errors,
                            warnings);
                }
            }
        }
    }

    private static void inspectScriptStep(
            String path,
            Map<String, Object> def,
            boolean inAuthSteps,
            boolean usesAuthPkg,
            Map<String, String> scripts,
            Set<String> loginVars,
            AtomicBoolean usesLogin,
            AtomicBoolean usesHermit114,
            AtomicBoolean usesSecretVars114,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        if (def == null) {
            return;
        }
        boolean credential = Boolean.TRUE.equals(def.get("credential"));
        ScanResult loginScan = new ScanResult();
        ScanResult authScan = new ScanResult();

        if (def.get("code") instanceof String code) {
            if (scanHermit114(code)) {
                usesHermit114.set(true);
            }
            scanSource(code, 0, code.length(), false, "login", loginScan);
            if (usesAuthPkg) {
                scanSource(code, 0, code.length(), false, "auth", authScan);
            }
        }
        if (scripts != null && def.get("use") instanceof List<?> libs) {
            for (Object item : libs) {
                if (item instanceof String name) {
                    String lib = scripts.get(name);
                    if (lib != null) {
                        scanSource(lib, 0, lib.length(), false, "login", loginScan);
                        if (usesAuthPkg) {
                            scanSource(lib, 0, lib.length(), false, "auth", authScan);
                        }
                    }
                }
            }
        }

        if (credential) {
            boolean touchesLogin = loginScan.memberAccess || loginScan.bareAccess;
            boolean touchesAuth = usesAuthPkg && (authScan.memberAccess || authScan.bareAccess);
            if (touchesLogin || touchesAuth) {
                usesSecretVars114.set(true);
            }
            if (!inAuthSteps && touchesLogin) {
                usesLogin.set(true);
                for (String field : loginScan.fields) {
                    if (isValidField("login", field) && !loginVars.contains(field)) {
                        errors.add(ConchValidator.issue(
                                ValidationCodes.UNDECLARED_VARIABLE,
                                path + ".code",
                                "引用了未声明的登录变量 " + field));
                    }
                }
            }
        } else if (warnings != null && (loginScan.memberAccess || (usesAuthPkg && authScan.memberAccess))) {
            warnings.add(ConchValidator.issue(ValidationCodes.SCRIPT_STEP, path, PLAIN_SCRIPT_SECRET_WARNING));
        }
    }

    static boolean scanHermit114(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        ScanResult result = new ScanResult();
        scanSource(code, 0, code.length(), true, "hermit", result);
        return result.hermit114;
    }

    private static final class ScanResult {
        boolean memberAccess;
        boolean bareAccess;
        boolean hermit114;
        final LinkedHashSet<String> fields = new LinkedHashSet<>();
    }

    private static int scanSource(
            String code,
            int from,
            int limit,
            boolean checkHermit,
            String root,
            ScanResult out
    ) {
        int i = from;
        while (i < limit) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < limit) {
                char next = code.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < limit && code.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = code.indexOf("*/", i + 2);
                    i = (end < 0 || end + 2 > limit) ? limit : end + 2;
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                i = skipQuoted(code, i, limit, c);
                continue;
            }
            if (c == '`') {
                i = scanTemplateLiteral(code, i + 1, limit, checkHermit, root, out);
                continue;
            }
            if (checkHermit) {
                if (isIdentifierBoundary(code, i) && code.startsWith("hermit", i)
                        && isIdentifierEnd(code, i + "hermit".length(), limit)) {
                    int nextPos = matchMember(code, i + "hermit".length(), limit, out, true);
                    i = Math.max(i + 1, nextPos);
                    continue;
                }
            } else if (isIdentifierBoundary(code, i) && code.startsWith("vars", i)
                    && isIdentifierEnd(code, i + "vars".length(), limit)) {
                int afterRoot = matchVarsRoot(code, i + "vars".length(), limit, root);
                if (afterRoot > 0) {
                    int nextPos = matchMember(code, afterRoot, limit, out, false);
                    i = Math.max(i + 1, nextPos);
                    continue;
                }
            }
            i++;
        }
        return i;
    }

    private static int scanTemplateLiteral(
            String code,
            int start,
            int limit,
            boolean checkHermit,
            String root,
            ScanResult out
    ) {
        int i = start;
        while (i < limit) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '`') {
                return i + 1;
            }
            if (c == '$' && i + 1 < limit && code.charAt(i + 1) == '{') {
                int exprEnd = findMatchingBrace(code, i + 2, limit);
                scanSource(code, i + 2, exprEnd, checkHermit, root, out);
                i = exprEnd < limit ? exprEnd + 1 : limit;
                continue;
            }
            i++;
        }
        return limit;
    }

    private static int findMatchingBrace(String code, int start, int limit) {
        int depth = 1;
        int i = start;
        while (i < limit) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < limit) {
                char next = code.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < limit && code.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = code.indexOf("*/", i + 2);
                    i = (end < 0 || end + 2 > limit) ? limit : end + 2;
                    continue;
                }
            }
            if (c == '\'' || c == '"') {
                i = skipQuoted(code, i, limit, c);
                continue;
            }
            if (c == '`') {
                i = skipBacktickOnly(code, i + 1, limit);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return limit;
    }

    private static int skipBacktickOnly(String code, int start, int limit) {
        int i = start;
        while (i < limit) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '`') {
                return i + 1;
            }
            if (c == '$' && i + 1 < limit && code.charAt(i + 1) == '{') {
                int end = findMatchingBrace(code, i + 2, limit);
                i = end < limit ? end + 1 : limit;
                continue;
            }
            i++;
        }
        return limit;
    }

    private static int matchVarsRoot(String code, int afterVars, int limit, String root) {
        int p = skipSpaces(code, afterVars, limit);
        if (p < limit && code.charAt(p) == '.') {
            p = skipSpaces(code, p + 1, limit);
            if (code.startsWith(root, p) && isIdentifierEnd(code, p + root.length(), limit)) {
                return p + root.length();
            }
            return -1;
        }
        if (p < limit && code.charAt(p) == '[') {
            BracketLiteral lit = readBracketLiteral(code, p, limit);
            if (lit != null && root.equals(lit.value)) {
                return lit.afterBracket;
            }
        }
        return -1;
    }

    private static int matchMember(String code, int afterObj, int limit, ScanResult out, boolean forHermit) {
        int p = skipSpaces(code, afterObj, limit);
        // 支持可选链 ?. 写法
        if (p + 1 < limit && code.charAt(p) == '?' && code.charAt(p + 1) == '.') {
            p = skipSpaces(code, p + 2, limit);
            if (p < limit && code.charAt(p) == '[') {
                return matchBracketMember(code, p, limit, out, forHermit);
            }
            return matchDotIdentifier(code, p, limit, out, forHermit);
        }
        if (p < limit && code.charAt(p) == '.') {
            return matchDotIdentifier(code, skipSpaces(code, p + 1, limit), limit, out, forHermit);
        }
        if (p < limit && code.charAt(p) == '[') {
            return matchBracketMember(code, p, limit, out, forHermit);
        }
        if (!forHermit) {
            out.bareAccess = true;
        }
        return afterObj;
    }

    private static int matchDotIdentifier(String code, int start, int limit, ScanResult out, boolean forHermit) {
        int end = start;
        while (end < limit && isJsIdentPart(code.charAt(end))) {
            end++;
        }
        if (end > start) {
            String field = code.substring(start, end);
            recordField(field, out, forHermit);
        }
        return end;
    }

    private static int matchBracketMember(String code, int bracketPos, int limit, ScanResult out, boolean forHermit) {
        BracketLiteral lit = readBracketLiteral(code, bracketPos, limit);
        if (lit != null) {
            recordField(lit.value, out, forHermit);
            return lit.afterBracket;
        }
        if (!forHermit) {
            out.memberAccess = true;
        }
        return bracketPos + 1;
    }

    private static void recordField(String field, ScanResult out, boolean forHermit) {
        if (forHermit) {
            if (HERMIT_114_FUNCTIONS.contains(field)) {
                out.hermit114 = true;
            }
            return;
        }
        out.memberAccess = true;
        if (field != null && !field.isEmpty()) {
            out.fields.add(field);
        }
    }

    private record BracketLiteral(String value, int afterBracket) {
    }

    private static BracketLiteral readBracketLiteral(String code, int bracketPos, int limit) {
        int p = skipSpaces(code, bracketPos + 1, limit);
        if (p >= limit || (code.charAt(p) != '\'' && code.charAt(p) != '"')) {
            return null;
        }
        char quote = code.charAt(p);
        int start = p + 1;
        int end = start;
        while (end < limit && code.charAt(end) != quote && code.charAt(end) != '\\' && code.charAt(end) != '\n') {
            end++;
        }
        if (end >= limit || code.charAt(end) != quote) {
            return null;
        }
        int close = skipSpaces(code, end + 1, limit);
        if (close >= limit || code.charAt(close) != ']') {
            return null;
        }
        return new BracketLiteral(code.substring(start, end), close + 1);
    }

    private static boolean isValidField(String root, String field) {
        if (field == null || field.isEmpty()) {
            return false;
        }
        if ("auth".equals(root)) {
            return AuthenticatorChecks.isExportIdentifier(field);
        }
        char first = field.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || first == '_')) {
            return false;
        }
        for (int i = 1; i < field.length(); i++) {
            char c = field.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierBoundary(String code, int index) {
        if (index == 0) {
            return true;
        }
        char prev = code.charAt(index - 1);
        return prev != '.' && !isJsIdentPart(prev);
    }

    private static boolean isIdentifierEnd(String code, int index, int limit) {
        return index >= limit || !isJsIdentPart(code.charAt(index));
    }

    private static boolean isJsIdentPart(char c) {
        return c == '$' || c == '_' || Character.isLetterOrDigit(c);
    }

    private static int skipSpaces(String code, int index, int limit) {
        int p = index;
        while (p < limit && Character.isWhitespace(code.charAt(p))) {
            p++;
        }
        return p;
    }

    private static int skipQuoted(String code, int start, int limit, char quote) {
        int i = start + 1;
        while (i < limit) {
            char c = code.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return limit;
    }
}
