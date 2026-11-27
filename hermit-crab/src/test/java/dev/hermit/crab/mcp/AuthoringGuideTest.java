package dev.hermit.crab.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.sync.LocalConches;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringGuideTest {
    private static final Pattern FENCE = Pattern.compile("(?s)```yaml\\R(.*?)```");

    @TempDir
    Path temp;

    @Test
    void guideIsTheReferenceWithLinksRemovedAndExamplesThatValidate() throws Exception {
        byte[] repo = Files.readAllBytes(Path.of("..", "docs", "conch-reference.md"));
        assertThat(AuthoringGuide.referenceBytes()).isEqualTo(repo);
        String source = new String(repo, StandardCharsets.UTF_8);
        String guide = AuthoringGuide.text();
        assertThat(guide).isEqualTo(AuthoringGuide.stripDocLinks(source));
        assertThat(guide).contains("从 HAR 写规则").contains("conch: demo_status").contains("conch: sample_oa");
        assertThat(guide.indexOf("先和用户确定要做哪些功能"))
                .as("先确认功能，再分析 HAR")
                .isPositive()
                .isLessThan(guide.indexOf("按时间看 HAR"));
        int quick = guide.indexOf("## 步骤速查");
        int har = guide.indexOf("## 从 HAR 写规则");
        assertThat(quick).as("速查在最前面").isPositive().isLessThan(har);
        assertThat(guide.substring(quick, har).getBytes(StandardCharsets.UTF_8).length)
                .as("速查不超过 3 KB")
                .isLessThanOrEqualTo(3 * 1024);
        assertThat(guide.substring(quick, har)).contains("- if:", "- repeat:", "retry:", "min_crab_version: 1.9.0");
        assertThat(guide).doesNotContain("](").doesNotContain("github.com");
        assertThat(guide).doesNotContain(
                "legacy_oa",
                "api.md",
                "Extractors",
                "LegacyOaEngineTest",
                "BuiltinFilters",
                "ConchParser",
                "ConchValidator",
                "InputPreparer",
                "Invoker",
                "RuleEngine",
                "ToolSnapshots",
                "OutputMask",
                "Redactor",
                "CrabVersion",
                "调试开关",
                "打开调试");

        String prose = guide.replaceAll("(?s)```.*?```", "");
        assertThat(prose).doesNotContain("http://").doesNotContain("https://").doesNotContain("](").doesNotContain("github.com");

        CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("home")), CrabConfig.defaults());
        try {
            Matcher matcher = FENCE.matcher(guide);
            int packages = 0;
            while (matcher.find()) {
                String yaml = matcher.group(1);
                if (!yaml.lines().anyMatch(line -> line.startsWith("conch:"))) {
                    continue;
                }
                packages++;
                LocalConches.CheckResult checked = runtime.localConches().check(yaml.getBytes(StandardCharsets.UTF_8));
                assertThat(codes(checked.errors()))
                        .as(yaml.lines().filter(line -> line.startsWith("conch:")).findFirst().orElse(""))
                        .isEmpty();
                assertThat(codes(checked.warnings())).doesNotContain("LITERAL_CREDENTIAL");
                if (yaml.contains("conch: demo_status") || yaml.contains("conch: sample_oa")) {
                    assertThat(checked.warnings()).isEmpty();
                }
            }
            assertThat(packages).isGreaterThanOrEqualTo(4);
        } finally {
            runtime.close();
        }
    }

    @Test
    void sectionsSubsectionsAndSearchWorkAndIgnoreFencedCodeHeadings() {
        List<AuthoringGuide.Section> sections = AuthoringGuide.sections();
        assertThat(sections).isNotEmpty();
        assertThat(sections).extracting(AuthoringGuide.Section::id)
                .contains("quick_reference", "from_har", "fields", "discoverability", "groups", "mcp_upstream",
                        "sso_login", "session_expiry", "steps", "templates_scripts", "local_dev");
        assertThat(AuthoringGuide.quickReference().id()).isEqualTo("quick_reference");
        assertThat(AuthoringGuide.quickReference().bytes()).isLessThanOrEqualTo(3 * 1024);

        // 代码块内的 # 注释不得被当成章节
        assertThat(sections).extracting(AuthoringGuide.Section::title)
                .noneMatch(title -> title.startsWith("request：") || title.startsWith("assert："));

        AuthoringGuide.SectionMatch byId = AuthoringGuide.findSection("session_expiry").orElseThrow();
        assertThat(byId.title()).isEqualTo("非 401/403 的会话失效判定");
        assertThat(byId.parentId()).isNull();
        assertThat(byId.content()).contains("expired_when").doesNotContain("](");

        AuthoringGuide.SectionMatch byChineseTitle = AuthoringGuide.findSection("步骤").orElseThrow();
        assertThat(byChineseTitle.id()).isEqualTo("steps");
        assertThat(byChineseTitle.subsections()).contains(
                "ask（登录验证码）",
                "request（请求与编码）",
                "条件、循环和重试",
                "上传和下载文件",
                "请求超时、重定向与状态码处理",
                "extract 与 assert（提取与断言）");

        AuthoringGuide.SectionMatch templates = AuthoringGuide.findSection("templates_scripts").orElseThrow();
        assertThat(templates.subsections()).contains(
                "模板与过滤器",
                "保留变量与作用域",
                "输出与自动脱敏",
                "脚本与脚本库");

        AuthoringGuide.SectionMatch bySubsection = AuthoringGuide.findSection("上传和下载文件").orElseThrow();
        assertThat(bySubsection.parentId()).isEqualTo("steps");
        assertThat(bySubsection.content()).contains("multipart").contains("download");
        assertThat(bySubsection.bytes()).isLessThan(byChineseTitle.bytes());

        AuthoringGuide.SectionMatch byParentSub = AuthoringGuide.findSection("steps/条件、循环和重试").orElseThrow();
        assertThat(byParentSub.parentId()).isEqualTo("steps");
        assertThat(byParentSub.content()).contains("repeat");

        AuthoringGuide.SectionMatch starter = AuthoringGuide.findSection("starter").orElseThrow();
        assertThat(starter.id()).isEqualTo(AuthoringGuide.STARTER_SECTION);
        assertThat(starter.content()).contains("conch: my_system").contains("min_crab_version: 1.14.0");
        assertThat(AuthoringGuide.findSection("auth_component")).isPresent();
        assertThat(AuthoringGuide.findSection("授权器")).isPresent();
        assertThat(AuthoringGuide.findSection("starter_template")).isPresent();
        assertThat(AuthoringGuide.findSection("起始模板")).isPresent();

        AuthoringGuide.SectionMatch all = AuthoringGuide.findSection("ALL").orElseThrow();
        assertThat(all.id()).isEqualTo("all");
        assertThat(all.content()).isEqualTo(AuthoringGuide.text());

        assertThat(AuthoringGuide.findSection("not_a_real_section")).isEmpty();

        List<AuthoringGuide.SearchHit> hits = AuthoringGuide.search("expired_when");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo("session_expiry");
        assertThat(hits.get(0).excerpts()).isNotEmpty();

        // 独立测试围栏代码块内的 ## 标题不被切章
        List<AuthoringGuide.Section> synthetic = AuthoringGuide.parseSections("""
                # 标题

                ## 第一章

                ```yaml
                ## 这不是章节
                ### 也不是子章节
                ```

                ### 真实子章节

                正文
                """);
        assertThat(synthetic).hasSize(1);
        assertThat(synthetic.get(0).title()).isEqualTo("第一章");
        assertThat(synthetic.get(0).subsectionTitles()).containsExactly("真实子章节");
    }

    @Test
    void starterTemplateValidatesCleanlyWithZeroWarningsAndAllRecoverySectionsExist() {
        assertThat(AuthoringGuide.pitfalls()).hasSize(10);
        assertThat(AuthoringGuide.pitfalls()).allSatisfy(item -> assertThat(item).contains("❌").contains("✅"));

        CrabRuntime runtime = CrabRuntime.open(new CrabPaths(temp.resolve("starter")), CrabConfig.defaults());
        try {
            for (String yaml : List.of(
                    AuthoringGuide.starterTemplate(),
                    AuthoringGuide.starterAuthTemplate(),
                    AuthoringGuide.starterAuthUseTemplate())) {
                LocalConches.CheckResult checked = runtime.localConches().check(yaml.getBytes(StandardCharsets.UTF_8));
                assertThat(checked.ok()).isTrue();
                assertThat(codes(checked.errors())).isEmpty();
                assertThat(codes(checked.warnings())).isEmpty();
            }
            runtime.debug().setEnabled(true, dev.hermit.crab.store.Actors.CONSOLE);
            LocalConches.CheckResult mcpStarter = runtime.localConches().check(
                    AuthoringGuide.starterMcpTemplate().getBytes(StandardCharsets.UTF_8));
            assertThat(mcpStarter.ok()).isTrue();
            assertThat(codes(mcpStarter.errors())).isEmpty();
            assertThat(AuthoringGuide.starterSections()).hasSize(4);
            for (var entry : AuthoringGuide.starterSections()) {
                assertThat(AuthoringGuide.findSection(entry.get("id"))).isPresent();
            }
        } finally {
            runtime.close();
        }

        List<String> allCodes = List.of(
                "PARSE",
                "LITERAL_CREDENTIAL",
                "UNKNOWN_FUTURE_CODE",
                dev.hermit.common.validate.ValidationCodes.MIN_CRAB_VERSION,
                dev.hermit.common.validate.ValidationCodes.CONCH_NAME,
                dev.hermit.common.validate.ValidationCodes.DRIVER,
                dev.hermit.common.validate.ValidationCodes.MISSING_TITLE,
                dev.hermit.common.validate.ValidationCodes.MISSING_CREDENTIALS,
                dev.hermit.common.validate.ValidationCodes.MISSING_OUTPUT,
                dev.hermit.common.validate.ValidationCodes.MISSING_EXAMPLES,
                dev.hermit.common.validate.ValidationCodes.PLAINTEXT_CREDENTIAL,
                dev.hermit.common.validate.ValidationCodes.CONFIG_SECRET,
                dev.hermit.common.validate.ValidationCodes.UNDECLARED_CREDENTIAL,
                dev.hermit.common.validate.ValidationCodes.UNDECLARED_CONFIG,
                dev.hermit.common.validate.ValidationCodes.UNDECLARED_INPUT,
                dev.hermit.common.validate.ValidationCodes.UNDECLARED_VARIABLE,
                dev.hermit.common.validate.ValidationCodes.LOGIN_DEFAULT,
                dev.hermit.common.validate.ValidationCodes.CREDENTIAL_IN_OUTPUT,
                dev.hermit.common.validate.ValidationCodes.RESERVED_VARIABLE,
                dev.hermit.common.validate.ValidationCodes.TEMPLATE_SYNTAX,
                dev.hermit.common.validate.ValidationCodes.FLOW_STEP,
                dev.hermit.common.validate.ValidationCodes.REQUEST_BODY,
                dev.hermit.common.validate.ValidationCodes.SCRIPT_STEP,
                dev.hermit.common.validate.ValidationCodes.SESSION_EXPIRY,
                dev.hermit.common.validate.ValidationCodes.AUTH_ORIGIN,
                dev.hermit.common.validate.ValidationCodes.UNKNOWN_GROUP,
                dev.hermit.common.validate.ValidationCodes.HOSTNAME,
                dev.hermit.common.validate.ValidationCodes.DUPLICATE_ACTION,
                dev.hermit.common.validate.ValidationCodes.MCP_UPSTREAM);
        for (String code : allCodes) {
            AuthoringCalls.Recovery recovery = AuthoringCalls.recoveryFor(
                    List.of(new ValidationIssue(code, "path", "msg")));
            assertThat(recovery.fixHints()).as("fixHints for %s", code).isNotEmpty();
            assertThat(recovery.suggestedSections()).as("suggestedSections for %s", code).isNotEmpty();
            for (String section : recovery.suggestedSections()) {
                assertThat(AuthoringGuide.findSection(section))
                        .as("section %s suggested for %s", section, code)
                        .isPresent();
            }
        }
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        ArrayList<String> codes = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            codes.add(issue.code() + " " + issue.path() + " " + issue.message());
        }
        return codes;
    }
}
