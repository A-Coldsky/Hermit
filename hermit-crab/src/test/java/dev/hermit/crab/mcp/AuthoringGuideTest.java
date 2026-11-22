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

    private static List<String> codes(List<ValidationIssue> issues) {
        ArrayList<String> codes = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            codes.add(issue.code() + " " + issue.path() + " " + issue.message());
        }
        return codes;
    }
}
