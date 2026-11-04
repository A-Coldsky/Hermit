package dev.hermit.reef.admin;

import java.time.Instant;

public record UserSummary(
        String username,
        AdminRole role,
        boolean disabled,
        Instant createdAt,
        int tokenCount) {
}
