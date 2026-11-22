package dev.hermit.it.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 写规则测验的判分。平时只验证题目和判分本身：参考答案得满分，典型的写错能被判出来。
 * 给模型打分是手动的：把各轮答案放进一个目录，带 {@code -Dquiz.answers=目录} 运行，见 docs/development.md。
 */
class RuleQuizTest {

    static Stream<RuleQuiz.Task> tasks() {
        return RuleQuiz.TASKS.stream();
    }

    @ParameterizedTest
    @MethodSource("tasks")
    void referenceAnswersPass(RuleQuiz.Task task) {
        RuleQuiz.Grade grade = RuleQuiz.grade(task, task.reference());
        assertThat(grade.feedback()).as(task.id()).isEmpty();
        assertThat(grade.valid()).isTrue();
        assertThat(grade.correct()).isTrue();
        assertThat(task.prompt()).contains(RuleQuiz.BASE).contains("`run`");
    }

    @Test
    void aMisspelledKeyIsCaughtByValidationWithAHint() {
        RuleQuiz.Task task = RuleQuiz.task("q5_pages");
        RuleQuiz.Grade grade = RuleQuiz.grade(task, task.reference().replace("until:", "while:"));
        assertThat(grade.valid()).isFalse();
        assertThat(grade.feedback()).anySatisfy(line -> assertThat(line).contains("是不是想写 until？"));
    }

    @Test
    void aRuleThatValidatesButBehavesWrongIsCaughtByTheTrial() {
        RuleQuiz.Task confirm = RuleQuiz.task("q3_confirm");
        // 不管要不要确认都发 confirm。
        String always = confirm.reference().replace("contains: \"请再次确认\"", "status: 200");
        RuleQuiz.Grade grade = RuleQuiz.grade(confirm, always);
        assertThat(grade.valid()).isTrue();
        assertThat(grade.correct()).isFalse();
        assertThat(grade.feedback()).anySatisfy(line -> assertThat(line).contains("试跑 2"));

        RuleQuiz.Task retry = RuleQuiz.task("q8_retry");
        RuleQuiz.Grade none = RuleQuiz.grade(retry, retry.reference().replace("times: 3", "times: 1"));
        assertThat(none.valid()).isTrue();
        assertThat(none.correct()).isFalse();

        RuleQuiz.Task pages = RuleQuiz.task("q7_truncated");
        RuleQuiz.Grade many = RuleQuiz.grade(pages, pages.reference().replace("max: 5", "max: 8"));
        assertThat(many.correct()).isFalse();
        assertThat(many.feedback()).anySatisfy(line -> assertThat(line).contains("请求太多"));
    }

    /**
     * 目录结构：{@code <目录>/<题号>/1.yaml、2.yaml…}，数字是第几轮。第 1 轮是模型只看题面和说明写的；
     * 之后每一轮是它看了上一轮的判分说明后改的。报告写到 {@code <目录>/report.md}。
     */
    @Test
    @EnabledIfSystemProperty(named = "quiz.answers", matches = ".+")
    void scoreAnswers() throws IOException {
        Path root = Path.of(System.getProperty("quiz.answers"));
        StringBuilder report = new StringBuilder("| 题 | 考什么 | 第 1 轮校验 | 第 1 轮试跑 | 最终 | 轮数 |\n| --- | --- | --- | --- | --- | --- |\n");
        int answered = 0;
        int firstValid = 0;
        int firstCorrect = 0;
        int finalCorrect = 0;
        int rounds = 0;
        for (RuleQuiz.Task task : RuleQuiz.TASKS) {
            List<RuleQuiz.Grade> grades = new ArrayList<>();
            for (int round = 1; Files.isRegularFile(root.resolve(task.id()).resolve(round + ".yaml")); round++) {
                Path file = root.resolve(task.id()).resolve(round + ".yaml");
                RuleQuiz.Grade grade = RuleQuiz.grade(task, Files.readString(file));
                grades.add(grade);
                Files.writeString(root.resolve(task.id()).resolve(round + ".feedback.txt"),
                        grade.correct() ? "通过\n" : String.join("\n", grade.feedback()) + "\n");
            }
            if (grades.isEmpty()) {
                report.append("| ").append(task.id()).append(" | ").append(task.covers()).append(" | 没有答案 | | | |\n");
                continue;
            }
            answered++;
            RuleQuiz.Grade first = grades.get(0);
            RuleQuiz.Grade last = grades.get(grades.size() - 1);
            firstValid += first.valid() ? 1 : 0;
            firstCorrect += first.correct() ? 1 : 0;
            finalCorrect += last.correct() ? 1 : 0;
            rounds += grades.size();
            report.append("| ").append(task.id()).append(" | ").append(task.covers())
                    .append(" | ").append(first.valid() ? "通过" : "不通过")
                    .append(" | ").append(first.correct() ? "正确" : "不正确")
                    .append(" | ").append(last.correct() ? "正确" : "不正确")
                    .append(" | ").append(grades.size()).append(" |\n");
        }
        if (answered > 0) {
            report.append(String.format(Locale.ROOT,
                    "%n答了 %d 题。第 1 轮校验通过 %d/%d，第 1 轮试跑正确 %d/%d，最终正确 %d/%d，平均 %.2f 轮。%n",
                    answered, firstValid, answered, firstCorrect, answered, finalCorrect, answered,
                    (double) rounds / answered));
        }
        Files.writeString(root.resolve("report.md"), report.toString());
        System.out.println(report);
        assertThat(answered).as("目录里没有任何答案").isPositive();
    }
}
