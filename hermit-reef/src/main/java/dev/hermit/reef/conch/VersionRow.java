package dev.hermit.reef.conch;

import java.time.Instant;

record VersionRow(
        long id,
        String conch,
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
        String diffJson,
        long pullCount,
        String authUse) {
}
