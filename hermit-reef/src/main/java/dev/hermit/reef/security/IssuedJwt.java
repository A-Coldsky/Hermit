package dev.hermit.reef.security;

import java.time.Instant;

public record IssuedJwt(String token, Instant expiresAt) {
}
