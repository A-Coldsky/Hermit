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
    static final String URI = "hermit://authoring/guide";
    static final String ALL_SECTION = "all";
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
