package dev.hermit.reef.conch;

import com.fasterxml.jackson.databind.JsonNode;
import dev.hermit.common.validate.ValidationIssue;
import java.time.Instant;
import java.util.List;

record SubmissionRow(
        long id,
        String conch,
        String version,
        String sha256,
        String minCrabVersion,
        String driver,
        String title,
        String yamlPath,
        String validationJson,
        String diffJson,
        String submitNote,
        SubmissionStatus status,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewComment) {
}

record SubmissionSummary(
        long id,
        String conch,
        String version,
        String sha256,
        String minCrabVersion,
        String driver,
        String title,
        SubmissionStatus status,
        String submitNote,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewComment,
        int warningCount) {
}

record SubmissionDetail(
        long id,
        String conch,
        String version,
        String sha256,
        String minCrabVersion,
        String driver,
        String title,
        SubmissionStatus status,
        String submitNote,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String reviewComment,
        List<ValidationIssue> warnings,
        JsonNode diff,
        String currentPublishedVersion,
        boolean conchDisabled,
        boolean versionConflict) {
}

record PendingCountResponse(long count) {
}
