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
    static final String STARTER_AUTH_SECTION = "starter_auth";
    static final String STARTER_AUTH_USE_SECTION = "starter_auth_use";
    static final String STARTER_MCP_SECTION = "starter_mcp";
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
            Map.entry("让其他 AI 找得到", "discoverability"),
            Map.entry("能力分组", "groups"),
            Map.entry("MCP 上游包", "mcp_upstream"),
            Map.entry("HTTP 方法、只读和幂等是不同概念", "http_semantics"),
            Map.entry("同一账号的只读能力并行", "concurrent_reads"),
            Map.entry("门户和 SSO 登录", "sso_login"),
            Map.entry("授权器", "auth_component"),
            Map.entry("非 401/403 的会话失效判定", "session_expiry"),
            Map.entry("步骤", "steps"),
            Map.entry("模板、输出与脚本", "templates_scripts"),
            Map.entry("发布包与验收", "publish"),
            Map.entry("本地开发与导出", "local_dev"));

    private static final String STARTER_TEMPLATE = """
            conch: my_system
            version: 1.0.0
            driver: http
            min_crab_version: 1.13.0
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

    private static final String STARTER_AUTH_TEMPLATE = """
            conch: corp_sso
            version: 1.0.0
            driver: auth
            min_crab_version: 1.13.0
            title: 统一认证中心（请替换为实际 SSO 名称）
            description: 登录企业统一认证中心（SSO），为多个业务规则包提供共享会话 Cookie 与导出令牌。
            credentials:
              username:
                type: text
                label: 用户名
                required: true
                description: 统一认证账号
              password:
                type: secret
                label: 密码
                required: true
                description: 统一认证密码
            auth:
              origins:
                - https://sso.example.internal
              allow_hosts:
                - https://*.example.internal
              steps:
                - request:
                    method: POST
                    url: https://sso.example.internal/api/login
                    json:
                      username: "{{credential.username}}"
                      password: "{{credential.password}}"
                - assert: {status: 200}
                - extract:
                    access_token: {jsonpath: "$.data.access_token"}
              alive_check:
                request:
                  method: GET
                  url: https://sso.example.internal/api/session
                  headers:
                    Authorization: "Bearer {{login.access_token}}"
                expect: {status: 200}
              exports:
                access_token:
                  description: 统一认证签发的访问令牌
            """;

    private static final String STARTER_AUTH_USE_TEMPLATE = """
            conch: corp_portal_app
            version: 1.0.0
            driver: http
            min_crab_version: 1.13.0
            base_url: https://oa.example.internal
            title: 协同办公系统（请替换为实际业务系统名）
            description: 复用统一认证授权器登录，分页查询协同办公系统中的待办审批事项。
            auth:
              use: corp_sso
              min_version: 1.0.0
              exchange:
                - request:
                    method: POST
                    path: /api/sso/exchange
                    headers:
                      Authorization: "Bearer {{auth.access_token}}"
                - assert: {status: 200}
            actions:
              list_todos:
                title: 查询待办事项
                description: 分页查询当前登录人在协同办公系统中的待办审批事项列表。
                method: GET
                idempotent: true
                mcp: {expose: true, read_only: true}
                input:
                  page: {type: integer, default: 1, minimum: 1, description: 页码，从 1 开始}
                steps:
                  - request:
                      method: GET
                      path: /api/todos
                      query: {page: "{{input.page}}"}
                  - assert: {status: 200}
                  - extract:
                      items: {jsonpath: "$.data.items"}
                output:
                  items: {type: array, description: 待办事项列表}
                examples:
                  - input: {page: 1}
                    output:
                      items:
                        - {id: "101", title: 采购申请审批}
                notes: 只读接口，不会修改业务数据；若无需换票可省略 auth.exchange，直接在业务请求头写 Bearer {{auth.access_token}}。
                related: []
            """;

    private static final String STARTER_MCP_TEMPLATE = """
            conch: vendor_mcp
            version: 1.0.0
            driver: mcp
            min_crab_version: 1.13.0
            title: 上游 MCP 服务（请替换为实际系统名）
            description: 连接上游 Streamable HTTP MCP 服务并按刷新后的工具快照对外暴露能力。
            credentials:
              api_token:
                type: secret
                label: 访问令牌
                required: true
                description: 上游 MCP 服务的 Bearer 访问令牌
            upstream:
              url: https://mcp.example.internal/mcp
              transport: streamable_http
              auth:
                type: headers
              headers:
                Authorization: "Bearer {{credential.api_token}}"
            actions: {}
            """;

    private static final List<String> PITFALLS = List.of(
            "❌ 省略或写旧的 min_crab_version: 1.0.0（导致 if/repeat/retry/groups/origins/expired_when/授权器/引号下标 报错） → ✅ 新规则包统一写 min_crab_version: 1.13.0。",
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
    private static final String STARTER_AUTH_SECTION_CONTENT = buildStarterAuthSection();
    private static final String STARTER_AUTH_USE_SECTION_CONTENT = buildStarterAuthUseSection();
    private static final String STARTER_MCP_SECTION_CONTENT = buildStarterMcpSection();

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

    static String starterAuthTemplate() {
        return STARTER_AUTH_TEMPLATE;
    }

    static String starterAuthUseTemplate() {
        return STARTER_AUTH_USE_TEMPLATE;
    }

    static String starterMcpTemplate() {
        return STARTER_MCP_TEMPLATE;
    }

    static List<Map<String, String>> starterSections() {
        return List.of(
                Map.of("id", STARTER_SECTION, "driver", "http", "title", "标准 HTTP 规则包起始模板（独立登录与业务查询）"),
                Map.of("id", STARTER_AUTH_SECTION, "driver", "auth", "title", "可复用授权器（driver: auth）起始模板"),
                Map.of("id", STARTER_AUTH_USE_SECTION, "driver", "http", "title", "引用授权器的 HTTP 业务包（auth.use）起始模板"),
                Map.of("id", STARTER_MCP_SECTION, "driver", "mcp", "title", "上游 MCP 规则包（driver: mcp）起始骨架模板"));
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
        if (STARTER_AUTH_SECTION.equalsIgnoreCase(needle)
                || "授权器模板".equals(needle)) {
            return Optional.of(new SectionMatch(
                    STARTER_AUTH_SECTION,
                    "可直接复制的授权器（driver: auth）起始模板",
                    null,
                    STARTER_AUTH_SECTION_CONTENT,
                    utf8Bytes(STARTER_AUTH_SECTION_CONTENT),
                    List.of()));
        }
        if (STARTER_AUTH_USE_SECTION.equalsIgnoreCase(needle)
                || "引用授权器模板".equals(needle)) {
            return Optional.of(new SectionMatch(
                    STARTER_AUTH_USE_SECTION,
                    "可直接复制的引用授权器（auth.use）业务包起始模板",
                    null,
                    STARTER_AUTH_USE_SECTION_CONTENT,
                    utf8Bytes(STARTER_AUTH_USE_SECTION_CONTENT),
                    List.of()));
        }
        if (STARTER_MCP_SECTION.equalsIgnoreCase(needle)
                || "mcp模板".equalsIgnoreCase(needle)
                || "MCP起始模板".equalsIgnoreCase(needle)) {
            return Optional.of(new SectionMatch(
                    STARTER_MCP_SECTION,
                    "可直接复制的 MCP 上游包（driver: mcp）起始骨架模板",
                    null,
                    STARTER_MCP_SECTION_CONTENT,
                    utf8Bytes(STARTER_MCP_SECTION_CONTENT),
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
        sb.append("\n## 其它类型规则包起始模板\n\n");
        sb.append("- 可复用统一登录授权器（`driver: auth`）：`hermit_dev__guide(section=\"starter_auth\")`\n");
        sb.append("- 引用授权器的业务包（`driver: http` + `auth.use`）：`hermit_dev__guide(section=\"starter_auth_use\")`\n");
        sb.append("- 上游 MCP 规则包（`driver: mcp`）：`hermit_dev__guide(section=\"starter_mcp\")`\n");
        sb.append("\n## 写完后确认别的 AI 找得到\n\n");
        sb.append("其它 AI 的工具列表里看不到你写的能力，只能用 `list_systems` 和 `search_capabilities` 按文字查找。");
        sb.append("包和能力的 `title`、`description` 要用用户平时的叫法并带上同义说法，");
        sb.append("写法见章节 `discoverability`（让其他 AI 找得到）。\n");
        return sb.toString().stripTrailing();
    }

    private static String buildStarterAuthSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可直接复制的授权器（driver: auth）起始模板\n\n");
        sb.append("下面是一份能直接通过 `hermit_dev__validate`（0 错误、0 警告）的完整可复用授权器骨架：\n\n");
        sb.append("```yaml\n");
        sb.append(STARTER_AUTH_TEMPLATE);
        if (!STARTER_AUTH_TEMPLATE.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n\n");
        sb.append("## 授权器开发与验收要点\n\n");
        sb.append("1. 授权器只负责登录：必须写 `min_crab_version: 1.13.0`、`auth.origins`、`auth.steps`、`auth.alive_check`（请求写完整 `url`），**不能**写 `base_url`、`actions`、`groups`、`auth.expired_when`。\n");
        sb.append("2. 导出的令牌或票据在 `auth.exports` 声明，并且建议在 `auth.alive_check.request` 中引用至少一个 `{{login.导出名}}`，避免令牌早于会话过期时探活误判通过。\n");
        sb.append("3. 用 `hermit_dev__save_local` 保存后，让用户在 Crab 控制台「授权器」新建账号并填写凭证，随后调用 `hermit_dev__test_login`（或在控制台点「测试登录」）：Crab 会依次验证 `auth.steps`、`auth.alive_check` 和 `auth.exports` 全部非空。\n");
        sb.append("4. 编写和调试 `driver: auth` 需要勾选了「开发者工具」的全部权限 API Key。\n");
        return sb.toString().stripTrailing();
    }

    private static String buildStarterAuthUseSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可直接复制的引用授权器（auth.use）业务包起始模板\n\n");
        sb.append("下面是一份复用统一认证授权器的 `driver: http` 业务规则包骨架（0 错误、0 警告）：\n\n");
        sb.append("```yaml\n");
        sb.append(STARTER_AUTH_USE_TEMPLATE);
        if (!STARTER_AUTH_USE_TEMPLATE.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n\n");
        sb.append("## 引用授权器的业务包开发要点\n\n");
        sb.append("1. 先调用 `hermit_dev__auth_list` 查看本机已安装的授权器名称、版本、`allowHosts` 与 `exports` 导出变量名。\n");
        sb.append("2. 写了 `auth.use` 的业务包**不能**声明顶层 `credentials`，也**不能**写 `auth.steps` 或 `auth.origins`；读取授权器导出值统一写 `{{auth.导出名}}`。\n");
        sb.append("3. 若业务系统直接认授权器的同域 Cookie 或 `Bearer {{auth.xxx}}`，可省略 `auth.exchange`；若需用导出票据换取本系统独立会话，写在 `auth.exchange` 中。\n");
        sb.append("4. 用 `hermit_dev__save_local` 保存后，请用户在 Crab 控制台该业务包「账号」页新建账号并点「绑定授权器」确认站点地址，随后可用 `hermit_dev__test_login` 测换票、用 `hermit_dev__trial_run` 试跑业务能力。\n");
        return sb.toString().stripTrailing();
    }

    private static String buildStarterMcpSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 可直接复制的 MCP 上游包（driver: mcp）起始骨架模板\n\n");
        sb.append("开发者模式开启时，本地 MCP 规则包允许先以空 `actions: {}` 保存骨架，再连接上游拉取工具快照：\n\n");
        sb.append("```yaml\n");
        sb.append(STARTER_MCP_TEMPLATE);
        if (!STARTER_MCP_TEMPLATE.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n\n");
        sb.append("## MCP 规则包开发流程\n\n");
        sb.append("1. **鉴权选择**：请求头令牌写 `upstream.auth: {type: headers}` 并在 `upstream.headers` 写请求头模板，同时在顶层 `credentials` 声明秘密字段；OAuth 2.1 PKCE 写 `credentials: {}` 与 `upstream.auth: {type: oauth, client_id: \"...\"}`（端点支持 RFC 9728/8414 自动发现或显式填写）。无需鉴权时写 `credentials: {}` 与 `upstream.auth: {type: headers}`。\n");
        sb.append("2. **过滤工具（可选）**：可在顶层加 `tools: {include: [\"search_*\"], exclude: [\"admin_*\"]}` 控制刷新保留哪些上游工具。\n");
        sb.append("3. **保存与建号**：校验通过后调用 `hermit_dev__save_local` 保存，让用户在 Crab 控制台为该包新建账号（填写凭证，或 OAuth 点「连接上游」完成浏览器授权）。\n");
        sb.append("4. **刷新快照、审阅权限与试跑**：调用 `hermit_dev__mcp_refresh`（或用户在控制台点「刷新工具列表」）将上游 `tools/list` 写入本地 YAML 的 `actions` 与 `snapshot`。新加入的工具默认按保守策略设为 `read_only: false`、`destructive: true`、`confirm: true`（不直接采信上游注解）；请用 `hermit_dev__read_yaml` 审阅后将核实为只读的工具改为 `read_only: true`、`destructive: false`、`confirm: false` 并补充中文说明，保存后再用 `hermit_dev__trial_run` 试跑验证。注意：必须在刷新出至少一个工具后才能关闭开发者模式或导出发布到 Reef。\n");
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
