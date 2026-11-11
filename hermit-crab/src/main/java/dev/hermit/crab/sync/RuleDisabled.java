package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.validate.ValidationIssue;

/**
 * A version that is still on disk but the current validator will not load.
 * This is not a Reef disable and not a damaged signature.
 */
final class RuleDisabled {
    static final String LEAD = "已停用。这版规则暂时不能调用。";

    private RuleDisabled() {
    }

    static String explain(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return LEAD;
        }
        ValidationIssue first = issues.get(0);
        String message = first.message() == null || first.message().isBlank() ? first.code() : first.message();
        StringBuilder text = new StringBuilder(LEAD).append(message);
        if (!message.endsWith("。") && !message.endsWith(".")) {
            text.append('。');
        }
        if (first.line() != null) {
            String marker = "第 " + first.line() + " 行";
            if (!message.contains(marker)) {
                text.append(marker).append('。');
            }
        }
        if (issues.size() > 1) {
            text.append("另有 ").append(issues.size() - 1).append(" 处。");
        }
        String result = text.toString();
        return result.length() <= 180 ? result : result.substring(0, 180);
    }

    static String codes(List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder();
        if (issues == null) {
            return "";
        }
        for (ValidationIssue issue : issues) {
            if (issue.code() == null || issue.code().isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(issue.code());
        }
        return text.toString();
    }

    static String lines(List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder();
        if (issues == null) {
            return "";
        }
        for (ValidationIssue issue : issues) {
            if (issue.line() == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(issue.line());
        }
        return text.toString();
    }
}
