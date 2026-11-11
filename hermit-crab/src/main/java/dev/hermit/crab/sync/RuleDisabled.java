package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.validate.ValidationIssue;

/**
 * A version that is still on disk but the current validator will not load.
 * This is not a Reef disable and not a damaged signature.
 */
public final class RuleDisabled {
    static final String LEAD = "已停用。这版规则暂时不能调用。";
    public static final String LOCAL_HINT = "改规则后重新导入";
    public static final String UPDATE_OR_ROLLBACK = "更新到合规的新版本，或退回上一版";
    public static final String UPDATE_ONLY = "更新到合规的新版本";

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

    /** Next step, kept out of {@link #explain(List)} so the 180-character reason stays the validator text. */
    public static String fixHint(boolean local, boolean rollbackable) {
        if (local) {
            return LOCAL_HINT;
        }
        return rollbackable ? UPDATE_OR_ROLLBACK : UPDATE_ONLY;
    }

    /**
     * A higher version failed validation while the loaded version stayed in place.
     * The sentence names the incoming version, the first issue, its line, and the version still loaded.
     */
    public static String updateBlocked(String incoming, String current, List<ValidationIssue> located) {
        ValidationIssue first = located == null || located.isEmpty() ? null : located.get(0);
        String reason = first == null || first.message() == null || first.message().isBlank()
                ? (first == null || first.code() == null ? "校验未通过" : first.code())
                : first.message();
        if (reason.endsWith("。") || reason.endsWith(".")) {
            reason = reason.substring(0, reason.length() - 1);
        }
        String line = first != null && first.line() != null ? "，第 " + first.line() + " 行" : "";
        String head = "新版本 v" + incoming + " 装不上：";
        String tail = "。本机仍是可用的上一版 v" + current + "。";
        String full = head + reason + line + tail;
        if (full.length() <= 200) {
            return full;
        }
        int room = 200 - head.length() - tail.length();
        if (room < 1) {
            return full.substring(0, 200);
        }
        return head + (reason + line).substring(0, Math.min(room, (reason + line).length())) + tail;
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
