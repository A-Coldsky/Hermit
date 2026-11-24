package dev.hermit.reef.notice;

import java.time.Instant;

public record AnnouncementView(
        long id,
        String title,
        String body,
        AnnouncementLevel level,
        boolean pinned,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {
}
