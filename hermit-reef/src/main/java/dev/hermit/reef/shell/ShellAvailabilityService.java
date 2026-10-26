package dev.hermit.reef.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 停用是 Shell 级开关。已签名的 ZIP 不改写；分发清单不再列出它，发布和回滚都要等重新启用。
 */
@Service
public class ShellAvailabilityService {

    public static final String DISABLED = "shell is disabled";
    public static final String ALREADY_DISABLED = "shell is already disabled";
    public static final String NOT_DISABLED = "shell is not disabled";
    public static final int MAX_REASON_LENGTH = 500;

    private final ShellRepository shells;
    private final ShellAvailabilityRepository availability;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ShellAvailabilityService(ShellRepository shells, ShellAvailabilityRepository availability,
            AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.shells = shells;
        this.availability = availability;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ShellAvailabilityView disable(String actor, String shell, String reason) {
        requireActor(actor);
        String stored = normalizeReason(reason);
        List<VersionRow> rows = lockExisting(shell);
        AvailabilityRow current = availability.lock(shell);
        Instant at = clock.instant();
        if (current == null) {
            availability.insertDisabled(shell, at, actor, stored);
        } else if (current.disabled() || availability.updateDisabled(shell, at, actor, stored) != 1) {
            throw conflict(ALREADY_DISABLED);
        }
        auditService.record(actor, AuditAction.DISABLE, shell, currentVersion(rows), detail(stored));
        return view(shell, availability.lock(shell));
    }

    @Transactional
    public ShellAvailabilityView enable(String actor, String shell, String reason) {
        requireActor(actor);
        String stored = normalizeReason(reason);
        List<VersionRow> rows = lockExisting(shell);
        AvailabilityRow current = availability.lock(shell);
        if (current == null || !current.disabled() || availability.clearDisabled(shell) != 1) {
            throw conflict(NOT_DISABLED);
        }
        auditService.record(actor, AuditAction.ENABLE, shell, currentVersion(rows), detail(stored));
        return view(shell, availability.lock(shell));
    }

    public boolean isDisabled(String shell) {
        return shell != null && availability.isDisabled(shell);
    }

    private List<VersionRow> lockExisting(String shell) {
        if (shell == null || shell.isBlank() || shell.length() > 128 || !ShellPaths.singleSegment(shell)) {
            throw notFound();
        }
        List<VersionRow> rows = shells.lockShell(shell);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows;
    }

    private String detail(String reason) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", reason);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    static String normalizeReason(String reason) {
        if (reason == null) {
            throw ApiException.invalidInput();
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
            throw ApiException.invalidInput();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw ApiException.invalidInput();
            }
        }
        return trimmed;
    }

    private static ShellAvailabilityView view(String shell, AvailabilityRow row) {
        if (row == null || !row.disabled()) {
            return new ShellAvailabilityView(shell, false, null, null, null);
        }
        return new ShellAvailabilityView(shell, true, row.disabledAt(), row.disabledBy(), row.disabledReason());
    }

    private static String currentVersion(List<VersionRow> rows) {
        return rows.stream().filter(VersionRow::current).map(VersionRow::version).findFirst().orElse(null);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }
}
