package dev.hermit.reef.admin;

import java.time.Instant;

public record AdminAccount(
        long id,
        String username,
        String passwordHash,
        boolean disabled,
        AdminRole role,
        Instant createdAt,
        int tokenVersion) {

    @Override
    public String toString() {
        return "AdminAccount[id=" + id + ", username=" + username + ", disabled=" + disabled + ", role=" + role + "]";
    }
}
