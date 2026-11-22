package dev.hermit.reef.security;

import dev.hermit.reef.admin.AgentTokenService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 同一（令牌，实例）在内存里 60 秒内只写一次 {@code last_used_at} 和 {@code last_seen_at}。
 * 进程重启后重新计时。上报里的 crab 版本单独更新，不受这 60 秒限制。
 * 写下当前用户的实例行之后，删掉其他用户上相同 {@code instance_id} 的行。后一次写出的用户留下。
 */
@Component
public class AgentPresence {

    static final Duration MIN_INTERVAL = Duration.ofSeconds(60);
    static final int EVICT_AT = 10_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastWrite = new ConcurrentHashMap<>();

    public AgentPresence(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void seen(AgentPrincipal principal, String instanceId) {
        if (principal == null || principal.userId() == null || instanceId == null || instanceId.isBlank()) {
            return;
        }
        String key = key(principal, instanceId);
        Instant now = clock.instant();
        AtomicBoolean write = new AtomicBoolean(false);
        lastWrite.compute(key, (ignored, previous) -> {
            if (previous != null && Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0) {
                return previous;
            }
            write.set(true);
            return now;
        });
        if (lastWrite.size() > EVICT_AT) {
            Instant cutoff = now.minus(MIN_INTERVAL);
            lastWrite.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
            if (lastWrite.size() > EVICT_AT) {
                lastWrite.clear();
            }
        }
        if (!write.get()) {
            return;
        }
        if (principal.tokenId() != null) {
            jdbc.update(
                    "UPDATE hermit_agent_tokens SET last_used_at = ? WHERE id = ? AND revoked_at IS NULL",
                    Timestamp.from(now),
                    principal.tokenId());
        }
        upsertInstance(principal, instanceId, now);
    }

    public void noteCrabVersion(AgentPrincipal principal, String instanceId, String crabVersion) {
        if (principal == null || principal.userId() == null || instanceId == null) {
            return;
        }
        String stored = AgentTokenService.crabVersionForStore(crabVersion);
        if (stored == null) {
            return;
        }
        jdbc.update(
                "UPDATE hermit_crab_instances SET crab_version = ? WHERE user_id = ? AND instance_id = ? "
                        + "AND (crab_version IS NULL OR crab_version <> ?)",
                stored,
                principal.userId(),
                instanceId,
                stored);
    }

    private void upsertInstance(AgentPrincipal principal, String instanceId, Instant now) {
        Timestamp stamp = Timestamp.from(now);
        int updated = jdbc.update(
                "UPDATE hermit_crab_instances SET last_seen_at = ?, token_id = ? WHERE user_id = ? AND instance_id = ?",
                stamp,
                principal.tokenId(),
                principal.userId(),
                instanceId);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO hermit_crab_instances (user_id, instance_id, token_id, first_seen_at, "
                                + "last_seen_at, crab_version) VALUES (?, ?, ?, ?, ?, NULL)",
                        principal.userId(),
                        instanceId,
                        principal.tokenId(),
                        stamp,
                        stamp);
            } catch (DuplicateKeyException ex) {
                updated = jdbc.update(
                        "UPDATE hermit_crab_instances SET last_seen_at = ?, token_id = ? "
                                + "WHERE user_id = ? AND instance_id = ?",
                        stamp,
                        principal.tokenId(),
                        principal.userId(),
                        instanceId);
                if (updated == 0) {
                    return;
                }
            }
        }
        jdbc.update(
                "DELETE FROM hermit_crab_instances WHERE instance_id = ? AND user_id <> ?",
                instanceId,
                principal.userId());
    }

    int cacheSize() {
        return lastWrite.size();
    }

    private static String key(AgentPrincipal principal, String instanceId) {
        String token = principal.tokenId() == null ? "config:" + principal.userId() : "token:" + principal.tokenId();
        return token + "|" + instanceId;
    }
}
