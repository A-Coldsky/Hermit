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
        String externalUrl) {

    public enum Source {
        LOCAL,
        EXTERNAL,
        NONE
    }
}
