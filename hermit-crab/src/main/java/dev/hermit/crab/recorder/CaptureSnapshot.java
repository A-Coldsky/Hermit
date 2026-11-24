package dev.hermit.crab.recorder;

import java.time.Instant;
import java.util.List;

/**
 * Confirmed, memory-only, fully redacted capture snapshot created by the user in Crab console.
 * Can be exported as redacted HAR 1.2 or read by local developer-mode MCP authoring tools.
 */
public record CaptureSnapshot(
        String id,
        String title,
        Instant createdAt,
        String source,
        List<String> origins,
        List<String> cookieNames,
        boolean hasRedirects,
        boolean hasDownloads,
        List<RecordedEntry> entries
) {
    public CaptureSnapshot {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        source = source == null ? "chrome_launch" : source;
        origins = origins == null ? List.of() : List.copyOf(origins);
        cookieNames = cookieNames == null ? List.of() : List.copyOf(cookieNames);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
