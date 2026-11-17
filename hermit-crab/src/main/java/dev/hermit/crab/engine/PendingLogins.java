package dev.hermit.crab.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import dev.hermit.common.error.ErrorCode;

/**
 * 进程内的待输入登录。每个 {@code (origin, conch, account)} 至多一条。
 * 不进数据库；重启即丢。图片字节只留在这条记录里。
 */
public final class PendingLogins {

    static final int MAX_PENDING = 64;

    private static final int IMAGE_GAP_SECONDS = 2;

    private static final int IMAGE_MAX = 10;

    private static final int CODE_GAP_SECONDS = 60;

    private static final int CODE_MAX = 3;

    private final Clock clock;

    private final AtomicLong ids = new AtomicLong();

    private final LinkedHashMap<SessionKey, Pending> rows = new LinkedHashMap<>();

    public PendingLogins(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Draft(
            String version,
            String contentHash,
            int askIndex,
            String into,
            String kind,
            String label,
            String hint,
            int timeoutSeconds,
            boolean previousRequest,
            Map<String, Object> vars,
            byte[] image,
            String mediaType
    ) {
    }

    public record Pending(
            long id,
            String version,
            String contentHash,
            int askIndex,
            String into,
            String kind,
            String label,
            String hint,
            int timeoutSeconds,
            boolean previousRequest,
            Map<String, Object> vars,
            byte[] image,
            String mediaType,
            Instant createdAt,
            Instant expiresAt,
            int resendCount,
            Instant lastResendAt
    ) {
    }

    public record View(
            String kind,
            String label,
            String hint,
            Instant expiresAt,
            boolean hasImage,
            boolean canResend,
            int resendAfterSeconds
    ) {
    }

    public record Image(byte[] bytes, String mediaType) {
    }

    /** {@code code} 为空表示已经接下这次重发，计数已加。 */
    public record ResendDecision(ErrorCode code, String message, Pending pending) {
        public boolean accepted() {
            return code == null;
        }
    }

    public synchronized Pending current(SessionKey key) {
        Pending pending = rows.get(key);
        if (pending == null) {
            return null;
        }
        if (!clock.instant().isBefore(pending.expiresAt())) {
            rows.remove(key, pending);
            return null;
        }
        return pending;
    }

    public synchronized View view(SessionKey key) {
        Pending pending = current(key);
        if (pending == null) {
            return null;
        }
        return toView(pending, clock.instant());
    }

    public synchronized Image image(SessionKey key) {
        Pending pending = current(key);
        if (pending == null || pending.image() == null) {
            return null;
        }
        return new Image(Arrays.copyOf(pending.image(), pending.image().length), pending.mediaType());
    }

    public synchronized void store(SessionKey key, Draft draft) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(draft, "draft");
        Instant now = clock.instant();
        Pending created = materialize(draft, now);
        if (rows.containsKey(key)) {
            rows.put(key, created);
            return;
        }
        dropExpired(now);
        while (rows.size() >= MAX_PENDING) {
            SessionKey oldest = oldest();
            if (oldest == null) {
                break;
            }
            rows.remove(oldest);
        }
        rows.put(key, created);
    }

    public synchronized void discard(SessionKey key) {
        if (key != null) {
            rows.remove(key);
        }
    }

    public synchronized void discardConch(String conch) {
        if (conch == null) {
            return;
        }
        rows.keySet().removeIf(key -> conch.equals(key.conch()));
    }

    public synchronized int size() {
        return rows.size();
    }

    /**
     * 间隔没到或次数用完时不计数。接下之后即使上游失败也算一次，避免对着上游连打。
     */
    public synchronized ResendDecision beginResend(SessionKey key) {
        Pending pending = current(key);
        if (pending == null) {
            return new ResendDecision(ErrorCode.NOT_FOUND, LoginInputMessages.NONE, null);
        }
        if (!pending.previousRequest()) {
            return new ResendDecision(ErrorCode.CONFLICT, LoginInputMessages.NO_RESEND, null);
        }
        int max = maxResends(pending.kind());
        if (pending.resendCount() >= max) {
            return new ResendDecision(ErrorCode.CONFLICT, LoginInputMessages.QUOTA, null);
        }
        Instant now = clock.instant();
        int gap = gapSeconds(pending.kind());
        if (now.isBefore(pending.lastResendAt().plusSeconds(gap))) {
            return new ResendDecision(ErrorCode.CONFLICT, LoginInputMessages.TOO_FAST, null);
        }
        Pending updated = new Pending(
                pending.id(),
                pending.version(),
                pending.contentHash(),
                pending.askIndex(),
                pending.into(),
                pending.kind(),
                pending.label(),
                pending.hint(),
                pending.timeoutSeconds(),
                pending.previousRequest(),
                pending.vars(),
                pending.image(),
                pending.mediaType(),
                pending.createdAt(),
                pending.expiresAt(),
                pending.resendCount() + 1,
                now);
        rows.put(key, updated);
        return new ResendDecision(null, null, updated);
    }

    /**
     * 重发成功才把过期时间从现在重算。图片换新；失败时不要调用，旧图还在。
     * 返回 false 表示这条待输入已经不在了。
     */
    public synchronized boolean completeResend(SessionKey key, long id, byte[] image, String mediaType) {
        Pending pending = rows.get(key);
        if (pending == null || pending.id() != id) {
            return false;
        }
        Instant now = clock.instant();
        if (!now.isBefore(pending.expiresAt())) {
            rows.remove(key, pending);
            return false;
        }
        byte[] nextImage = pending.image();
        String nextType = pending.mediaType();
        if (image != null) {
            nextImage = Arrays.copyOf(image, image.length);
            nextType = mediaType;
        }
        rows.put(key, new Pending(
                pending.id(),
                pending.version(),
                pending.contentHash(),
                pending.askIndex(),
                pending.into(),
                pending.kind(),
                pending.label(),
                pending.hint(),
                pending.timeoutSeconds(),
                pending.previousRequest(),
                pending.vars(),
                nextImage,
                nextType,
                pending.createdAt(),
                now.plusSeconds(pending.timeoutSeconds()),
                pending.resendCount(),
                pending.lastResendAt()));
        return true;
    }

    private Pending materialize(Draft draft, Instant now) {
        byte[] image = draft.image() == null ? null : Arrays.copyOf(draft.image(), draft.image().length);
        return new Pending(
                ids.incrementAndGet(),
                draft.version(),
                draft.contentHash(),
                draft.askIndex(),
                draft.into(),
                draft.kind(),
                draft.label(),
                draft.hint(),
                draft.timeoutSeconds(),
                draft.previousRequest(),
                copyVars(draft.vars()),
                image,
                draft.mediaType(),
                now,
                now.plusSeconds(Math.max(draft.timeoutSeconds(), 0)),
                0,
                now);
    }

    private void dropExpired(Instant now) {
        rows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private SessionKey oldest() {
        SessionKey oldest = null;
        Instant created = null;
        for (Map.Entry<SessionKey, Pending> entry : rows.entrySet()) {
            Instant at = entry.getValue().createdAt();
            if (created == null || at.isBefore(created)) {
                created = at;
                oldest = entry.getKey();
            }
        }
        return oldest;
    }

    private static View toView(Pending pending, Instant now) {
        int max = maxResends(pending.kind());
        boolean quota = pending.resendCount() < max;
        boolean canResend = pending.previousRequest() && quota;
        int after = 0;
        if (canResend) {
            after = cooldownSeconds(pending.lastResendAt(), gapSeconds(pending.kind()), now);
        }
        return new View(
                pending.kind(),
                pending.label(),
                pending.hint(),
                pending.expiresAt(),
                pending.image() != null && pending.image().length > 0,
                canResend,
                after);
    }

    static int gapSeconds(String kind) {
        return "code".equals(kind) ? CODE_GAP_SECONDS : IMAGE_GAP_SECONDS;
    }

    static int maxResends(String kind) {
        return "code".equals(kind) ? CODE_MAX : IMAGE_MAX;
    }

    /** 剩余冷却秒数，不足一秒进一。已经可以重发时为 0。 */
    static int cooldownSeconds(Instant last, int gapSeconds, Instant now) {
        Instant ready = last.plusSeconds(gapSeconds);
        if (!now.isBefore(ready)) {
            return 0;
        }
        long millis = Duration.between(now, ready).toMillis();
        if (millis <= 0) {
            return 0;
        }
        long seconds = (millis + 999) / 1000;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private static Map<String, Object> copyVars(Map<String, Object> vars) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        if (vars != null) {
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> map) {
                    copy.put(entry.getKey(), new LinkedHashMap<>(map));
                } else {
                    copy.put(entry.getKey(), value);
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
