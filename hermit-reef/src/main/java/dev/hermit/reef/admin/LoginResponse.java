package dev.hermit.reef.admin;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt) {
}
