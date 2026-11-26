package dev.hermit.crab.sync;

import java.util.List;

import dev.hermit.common.validate.IssueLines;
import dev.hermit.common.validate.ValidationIssue;

/**
 * Crab-side name for {@link IssueLines}. {@code hermit_dev__validate} and Reef upload share that locator,
 * so a line reported here is the same line Reef returns.
 */
final class ImportIssueLines {
    private ImportIssueLines() {
    }

    static List<ValidationIssue> locate(String yaml, List<ValidationIssue> issues) {
        return IssueLines.locate(yaml, issues);
    }
}
