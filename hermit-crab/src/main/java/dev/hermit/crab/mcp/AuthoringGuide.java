package dev.hermit.crab.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Developer-mode authoring guide. The field reference is the current
 * {@code docs/conch-reference.md}, copied into the jar at build time.
 * Markdown links are removed so the text does not point at the repository or the web.
 */
final class AuthoringGuide {
    static final String URI = "hermit://conches/hermit_dev/guide";
    static final String ALL_SECTION = "all";
    static final String STARTER_SECTION = "starter";
    static final String QUICK_REFERENCE_ID = "quick_reference";
    private static final String RESOURCE = "authoring/conch-reference.md";
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^\\]\\n]*)\\]\\(([^)\\n]*)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("<https?://[^\\s>]+>");
    private static final int MAX_EXCERPTS_PER_SECTION = 3;
    private static final int MAX_EXCERPT_CHARS = 160;
    private static final Map<String, String> KNOWN_IDS = Map.ofEntries(
            Map.entry("步骤速查", QUICK_REFERENCE_ID),
            Map.entry("从 HAR 写规则", "from_har"),
            Map.entry("最小规则", "minimal"),
            Map.entry("字段与语义", "fields"),
            Map.entry("能力分组", "groups"),
            Map.entry("MCP 上游包", "mcp_upstream"),
            Map.entry("HTTP 方法、只读和幂等是不同概念", "http_semantics"),
            Map.entry("同一账号的只读能力并行", "concurrent_reads"),
            Map.entry("门户和 SSO 登录", "sso_login"),
            Map.entry("非 401/403 的会话失效判定", "session_expiry"),
            Map.entry("步骤", "steps"),
            Map.entry("模板、输出与脚本", "templates_scripts"),
            Map.entry("发布包与验收", "publish"),
            Map.entry("本地开发与导出", "local_dev"));

    private static final String STARTER_TEMPLATE = """
            conch: my_system
            version: 1.0.0
            driver: http
            min_crab_version: 1.12.0
            base_url: http://127.0.0.1:8080
            title: 示例系统（请替换为实际系统名）
            description: 登录后查询业务数据。凭证只声明字段，真实值由用户在 Crab 控制台填写。
            credentials:
              username:
                type: text
                label: 用户名
                required: true
                description: 登录账号
              password:
                type: secret
                label: 密码
                required: true
                description: 登录密码
            auth:
              steps:
                - request:
                    method: POST
                    path: /api/login
                    json:
                      username: "{{credential.username}}"
                      password: "{{credential.password}}"
                - assert: {status: 200, cookie_exists: SESSIONID}
              alive_check:
                request: {method: GET, path: /api/me}
                expect: {status: 200}
            actions:
              list_items:
                title: 查询列表
                description: 分页查询当前账号的业务列表。
                method: GET
                idempotent: true
                mcp: {expose: true, read_only: true}
                input:
                  page: {type: integer, default: 1, minimum: 1, description: 页码，从 1 开始}
                steps:
                  - request:
                      method: GET
                      path: /api/items
                      query: {page: "{{input.page}}"}
                  - assert: {status: 200}
                  - extract:
                      items: {jsonpath: "$.data.items"}
                output:
                  items: {type: array, description: 业务列表}
                examples:
                  - input: {page: 1}
                    output:
                      items:
                        - {id: "1", title: 示例条目}
                notes: 只读接口，不会修改业务数据。
                related: []
            """;

    private static final List<String> PITFALLS = List.of(
            "❌ 省略或写旧的 min_crab_version: 1.0.0（导致 if/repeat/retry/groups/origins/expired_when/引号下标 报错） → ✅ 新规则包统一写 min_crab_version: 1.12.0。",
            "❌ 省略 credentials、把密钥放进 config(type: secret) 或把抓包里的密码/Cookie/Bearer 原文写进 YAML → ✅ 无登录写 credentials: {}；有秘密放 credentials(type: secret)，步骤里写 {{credential.xxx}}，绝不把真值写进 YAML。",
            "❌ 在能力步骤里直接写 {{token}} 引用登录提取值，或手写 Cookie/Host/Content-Length 请求头 → ✅ 跨登录读取一律写 {{login.token}}（带连字符 Cookie 先在 auth.cookies 映射）；Cookie 由 Crab 自动管理，不要写 Cookie/Host/Content-Length 头。",
            "❌ 写成 assert: {status: \"200\"}（带引号）或 if: \"{{count}} > 0\" / while / elif → ✅ status 写数字（200 或 [200, 302]）；分支和循环用 if / repeat + until，比较大小先用 script 算成变量再用 equals 判断。",
            "❌ 在 if/until/assert 的 equals/not_equals/present/not_present 里写 {{var}}（如 present: \"{{next}}\"） → ✅ 变量判断键直接写变量名，不加双花括号：present: next、equals: {code: \"0\"}；repeat 的 collect 也写变量名：collect: {all_rows: rows}。",
            "❌ 用点号读带连字符的响应头（{{response.headers.content-type}}）或在 credential/login/input 后写方括号 → ✅ 带连字符用点号加引号下标：{{response.headers['content-type']}}；credential/login/config/input/now 一律用点号。",
            "❌ 同一 request 同时写 json 和 body/form/multipart，或需要 302 跳转时漏写 follow_redirects: true → ✅ body/form/json/multipart 四选一互斥；默认不跟随重定向，需跟随写 follow_redirects: true；跨认证域名登录要在 auth.origins 声明。",
            "❌ HTTP 规则包漏写 actions.<name>.examples、output，或省略 related 产生警告 → ✅ 每个 HTTP 能力必须写 title、description、output、examples；无关联能力显式写 related: []；只读查询标 idempotent: true 与 mcp: {expose: true, read_only: true}。",
            "❌ 在 script 里把 vars.response 当字符串、不写 credential: true 就读 vars.credential，或直接 return Uint8Array/Date → ✅ 响应正文是 vars.response.body（JSON 先 JSON.parse）；读凭证加 credential: true；字节数组先转 hermit.hex/base64，不能用 class/async/fetch。",
            "❌ HTTP 200 业务错误码或登录页 HTML 只靠默认 401/403，或试图修改 Reef 正式包/换包名绕过确认 → ✅ 业务码/登录页失效写 auth.expired_when（如 json_equals: {\"$.code\": 40100}）；Reef 正式包只读，改规则用 hermit_dev__save_local 存本地包，改已有本地包保持同名。");

    private static final String STARTER_SECTION_CONTENT = buildStarterSection();

    private static final byte[] REFERENCE = readReference();
    private static final String TEXT = stripDocLinks(new String(REFERENCE, StandardCharsets.UTF_8));
    private static final List<Section> SECTIONS = parseSections(TEXT);

    record Subsection(String title, String content, int bytes) {
    }

    record Section(String id, String title, String content, int bytes, List<Subsection> subsections) {
        List<String> subsectionTitles() {
            ArrayList<String> titles = new ArrayList<>(subsections.size());
            for (Subsection sub : subsections) {
                titles.add(sub.title());
            }
            return List.copyOf(titles);
        }
    }

    record SectionMatch(
            String id,
            String title,
            String parentId,
            String content,
            int bytes,
            List<String> subsections
    ) {
    }

    record SearchHit(
            String id,
            String title,
            int bytes,
            List<String> subsections,
            List<String> excerpts
    ) {
    }

    private AuthoringGuide() {
    }

    static String text() {
        return TEXT;
    }

    static byte[] referenceBytes() {
        return REFERENCE.clone();
    }

    static String starterTemplate() {
        return STARTER_TEMPLATE;
    }

    static List<String> pitfalls() {
        return PITFALLS;
    }

    static List<Section> sections() {
        return SECTIONS;
    }

    static Section quickReference() {
        for (Section section : SECTIONS) {
            if (QUICK_REFERENCE_ID.equals(section.id())) {
                return section;
            }
        }
        return SECTIONS.isEmpty() ? new Section(QUICK_REFERENCE_ID, "步骤速查", "", 0, List.of()) : SECTIONS.get(0);
    }

    static Optional<SectionMatch> findSection(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String needle = target.trim();
        if (ALL_SECTION.equalsIgnoreCase(needle)) {
            return Optional.of(new SectionMatch(
                    ALL_SECTION,
                    "规则包（Conch）参考",
                    null,
                    TEXT,
                    utf8Bytes(TEXT),
                    List.of()));
        }
        if (STARTER_SECTION.equalsIgnoreCase(needle)
                || "starter_template".equalsIgnoreCase(needle)
                || "起始模板".equals(needle)) {
            return Optional.of(new SectionMatch(
                    STARTER_SECTION,
                    "可直接复制的 HTTP 规则起始模板与避坑清单",
                    null,
                    STARTER_SECTION_CONTENT,
                    utf8Bytes(STARTER_SECTION_CONTENT),
                    List.of()));
        }
        for (Section section : SECTIONS) {
            if (section.id().equalsIgnoreCase(needle) || section.title().equalsIgnoreCase(needle)) {
                return Optional.of(new SectionMatch(
                        section.id(),
                        section.title(),
                        null,
                        section.content(),
                        section.bytes(),
                        section.subsectionTitles()));
            }
        }
        int slash = needle.indexOf('/');
        if (slash > 0 && slash < needle.length() - 1) {
            String parentKey = needle.substring(0, slash).trim();
            String subKey = needle.substring(slash + 1).trim();
            for (Section section : SECTIONS) {
                if (!section.id().equalsIgnoreCase(parentKey) && !section.title().equalsIgnoreCase(parentKey)) {
                    continue;
                }
                for (Subsection sub : section.subsections()) {
                    if (sub.title().equalsIgnoreCase(subKey)) {
                        return Optional.of(new SectionMatch(
                                section.id() + "/" + sub.title(),
                                sub.title(),
                                section.id(),
                                sub.content(),
                                sub.bytes(),
                                List.of()));
                    }
                }
            }
        }
        for (Section section : SECTIONS) {
            for (Subsection sub : section.subsections()) {
                if (sub.title().equalsIgnoreCase(needle)) {
                    return Optional.of(new SectionMatch(
                            section.id() + "/" + sub.title(),
                            sub.title(),
                            section.id(),
                            sub.content(),
                            sub.bytes(),
                            List.of()));
                }
            }
        }
        return Optional.empty();
    }

    static List<SearchHit> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        ArrayList<SearchHit> headingHits = new ArrayList<>();
        ArrayList<SearchHit> bodyHits = new ArrayList<>();
        for (Section section : SECTIONS) {
            boolean headingMatch = section.id().toLowerCase(Locale.ROOT).contains(needle)
                    || section.title().toLowerCase(Locale.ROOT).contains(needle)
                    || section.subsections().stream()
                            .anyMatch(sub -> sub.title().toLowerCase(Locale.ROOT).contains(needle));
            ArrayList<String> excerpts = new ArrayList<>();
            for (String line : section.content().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("```")) {
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).contains(needle)) {
                    excerpts.add(truncate(trimmed));
                    if (excerpts.size() >= MAX_EXCERPTS_PER_SECTION) {
                        break;
                    }
                }
            }
            if (!headingMatch && excerpts.isEmpty()) {
                continue;
            }
            SearchHit hit = new SearchHit(
                    section.id(),
                    section.title(),
                    section.bytes(),
                    section.subsectionTitles(),
                    List.copyOf(excerpts));
            if (headingMatch) {
                headingHits.add(hit);
            } else {
                bodyHits.add(hit);
            }
        }
        headingHits.addAll(bodyHits);
        return List.copyOf(headingHits);
    }

    static String stripDocLinks(String markdown) {
        String withoutLinks = MARKDOWN_LINK.matcher(markdown == null ? "" : markdown).replaceAll("$1");
        return AUTOLINK.matcher(withoutLinks).replaceAll("");
    }

    static List<Section> parseSections(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        String[] lines = markdown.split("(?<=\\n)");
        ArrayList<RawSection> rawSections = new ArrayList<>();
        RawSection current = null;
        char fenceChar = 0;
        int fenceLen = 0;
        for (String line : lines) {
            String trimmedStart = stripLeadingSpaces(line);
            int nextFence = fenceLength(trimmedStart);
            if (nextFence >= 3) {
                char ch = trimmedStart.charAt(0);
                if (fenceChar == 0) {
                    fenceChar = ch;
                    fenceLen = nextFence;
                } else if (fenceChar == ch && nextFence >= fenceLen && trimmedStart.substring(nextFence).trim().isEmpty()) {
                    fenceChar = 0;
                    fenceLen = 0;
                }
            }
            if (fenceChar == 0 && line.startsWith("## ")) {
                String title = line.substring(3).trim();
                current = new RawSection(title);
                rawSections.add(current);
            }
            if (current != null) {
                current.body.append(line);
            }
        }
        LinkedHashMap<String, Integer> usedIds = new LinkedHashMap<>();
        ArrayList<Section> parsed = new ArrayList<>(rawSections.size());
        for (int i = 0; i < rawSections.size(); i++) {
            RawSection raw = rawSections.get(i);
            String baseId = KNOWN_IDS.getOrDefault(raw.title, "section_" + (i + 1));
            int seen = usedIds.merge(baseId, 1, Integer::sum);
            String id = seen == 1 ? baseId : baseId + "_" + seen;
            String content = raw.body.toString().stripTrailing();
            List<Subsection> subsections = parseSubsections(content);
            parsed.add(new Section(id, raw.title, content, utf8Bytes(content), subsections));
        }
        return List.copyOf(parsed);
    }

    private static List<Subsection> parseSubsections(String sectionMarkdown) {
        String[] lines = sectionMarkdown.split("(?<=\\n)");
        ArrayList<RawSection> rawSubs = new ArrayList<>();
        RawSection current = null;
        char fenceChar = 0;
        int fenceLen = 0;
        for (String line : lines) {
            String trimmedStart = stripLeadingSpaces(line);
            int nextFence = fenceLength(trimmedStart);
            if (nextFence >= 3) {
                char ch = trimmedStart.charAt(0);
                if (fenceChar == 0) {
                    fenceChar = ch;
                    fenceLen = nextFence;
                } else if (fenceChar == ch && nextFence >= fenceLen && trimmedStart.substring(nextFence).trim().isEmpty()) {
                    fenceChar = 0;
                    fenceLen = 0;
                }
            }
            if (fenceChar == 0 && line.startsWith("### ")) {
                String title = line.substring(4).trim();
                current = new RawSection(title);
                rawSubs.add(current);
            }
            if (current != null) {
                current.body.append(line);
            }
        }
        ArrayList<Subsection> subs = new ArrayList<>(rawSubs.size());
        for (RawSection raw : rawSubs) {
            String content = raw.body.toString().stripTrailing();
            subs.add(new Subsection(raw.title, content, utf8Bytes(content)));
        }
        return List.copyOf(subs);
    }

    private static String stripLeadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private static int fenceLength(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        char first = text.charAt(0);
        if (first != '`' && first != '~') {
            return 0;
        }
        int i = 0;
        while (i < text.length() && text.charAt(i) == first) {
            i++;
        }
        return i;
    }

    private static String truncate(String line) {
        if (line.length() <= MAX_EXCERPT_CHARS) {
            return line;
        }
        return line.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class RawSection {
        final String title;
        final StringBuilder body = new StringBuilder();

        RawSection(String title) {
            this.title = title;
        }
    }

    private static String buildStarterSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可直接复制的 HTTP 规则起始模板\n\n");
        sb.append("下面是一份能直接通过 `hermit_dev__validate`（0 错误、0 警告）的完整 HTTP 规则包骨架，可直接复制后按抓包结果修改：\n\n");
        sb.append("```yaml\n");
        sb.append(STARTER_TEMPLATE);
        if (!STARTER_TEMPLATE.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n\n");
        sb.append("## 高频避坑清单\n\n");
        for (int i = 0; i < PITFALLS.size(); i++) {
            sb.append(i + 1).append(". ").append(PITFALLS.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static byte[] readReference() {
        ClassLoader loader = AuthoringGuide.class.getClassLoader();
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "构建期没有把 docs/conch-reference.md 收进 classpath：" + RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("构建期没有把 docs/conch-reference.md 收进 classpath：" + RESOURCE);
        }
    }
}
