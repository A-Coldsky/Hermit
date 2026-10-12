package dev.hermit.reef.shell;

import java.time.Instant;

record VersionRow(
        long id,
        String shell,
        String version,
        String sha256,
        String minCrabVersion,
        String status,
        String uploadedBy,
        Instant uploadedAt,
        String publishedBy,
        Instant publishedAt,
        boolean current,
        String validationJson,
        String diffJson) {
}
