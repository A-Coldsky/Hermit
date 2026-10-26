package dev.hermit.crab.engine;

import java.util.List;

import dev.hermit.common.validate.ValidationIssue;

public final class ConchRejectedException extends RuntimeException {

    private final List<ValidationIssue> issues;

    public ConchRejectedException(List<ValidationIssue> issues) {
        super(summarize(issues));
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    private static String summarize(List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder();
        for (ValidationIssue issue : issues) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(issue.code()).append(' ').append(issue.path()).append(' ').append(issue.message());
        }
        return text.toString();
    }
}
