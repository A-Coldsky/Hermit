package dev.hermit.reef.admin;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.security.TokenDigests;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 配置令牌的作废摘要。命中当前 {@code HERMIT_REEF_AGENT_TOKEN} 才拒绝；换一串新值后旧行不再匹配。
 */
@Component
public class ConfigTokenVoids {

    private final JdbcTemplate jdbc;
    private final ReefProperties properties;
    private final Clock clock;

    public ConfigTokenVoids(JdbcTemplate jdbc, ReefProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    boolean currentIsVoided() {
        return currentVoidedAt().isPresent();
    }

    Optional<Instant> currentVoidedAt() {
        String token = properties.configuredAgentToken();
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(voidedAt(TokenDigests.sha256Hex(token)));
    }

    /**
     * 记下当前配置值的摘要。没有配置令牌时为空。已经作废过则 {@code created} 为 false，时间用库里的那次。
     */
    Optional<Mark> voidCurrent() {
        String token = properties.configuredAgentToken();
        if (token == null) {
            return Optional.empty();
        }
        String hash = TokenDigests.sha256Hex(token);
        Instant existing = voidedAt(hash);
        if (existing != null) {
            return Optional.of(new Mark(existing, false));
        }
        Instant now = clock.instant();
        try {
            jdbc.update(
                    "INSERT INTO hermit_voided_config_tokens (token_hash, voided_at) VALUES (?, ?)",
                    hash,
                    Timestamp.from(now));
            return Optional.of(new Mark(now, true));
        } catch (DataIntegrityViolationException ex) {
            Instant stored = voidedAt(hash);
            if (stored == null) {
                throw ex;
            }
            return Optional.of(new Mark(stored, false));
        }
    }

    private Instant voidedAt(String hash) {
        return jdbc.query(
                "SELECT voided_at FROM hermit_voided_config_tokens WHERE token_hash = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Timestamp stamp = rs.getTimestamp("voided_at");
                    return stamp == null ? null : stamp.toInstant();
                },
                hash);
    }

    record Mark(Instant voidedAt, boolean created) {
    }
}
