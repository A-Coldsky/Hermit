package dev.hermit.reef.download;

import java.time.Instant;

public record CrabDownloadInfo(
        boolean available,
        Source source,
        String fileName,
        Long sizeBytes,
        String sha256,
        Instant updatedAt,
        String latestCrabVersion,
        String downloadUrl,
        String externalUrl,
        String uploadedBy,
        boolean bundledAvailable,
        String bundledFileName,
        Long bundledSizeBytes,
        String bundledSha256,
        PreviousUpload previousUpload,
        boolean pinnedByConfig) {

    public enum Source {
        UPLOADED,
        LOCAL,
        BUNDLED,
        EXTERNAL,
        NONE
    }

    public record PreviousUpload(
            String fileName,
            long sizeBytes,
            String sha256,
            Instant uploadedAt,
            String uploadedBy) {
    }
}
