package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.validate.ConchValidator;
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
 * 停用是 Conch 级开关。已签名的 ZIP 不改写；分发清单不再列出它，发布和回滚都要等重新启用。
 */
@Service
public class ConchAvailabilityService {

    public static final String DISABLED = "规则包已停用";
    public static final String ALREADY_DISABLED = "规则包已经停用";
    public static final String NOT_DISABLED = "规则包尚未停用";
    public static final int MAX_REASON_LENGTH = 500;

    private final ConchRepository conches;
    private final ConchAvailabilityRepository availability;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuthDependencies authDependencies;

    public ConchAvailabilityService(ConchRepository conches, ConchAvailabilityRepository availability,
            AuditService auditService, ObjectMapper objectMapper, Clock clock,
            AuthDependencies authDependencies) {
        this.conches = conches;
        this.availability = availability;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.authDependencies = authDependencies;
    }

    @Transactional
    public ConchAvailabilityView disable(String actor, String conch, String reason) {
        requireActor(actor);
        String stored = normalizeReason(reason);
        List<VersionRow> rows = lockExisting(conch);
        List<String> consumers = authDependencies.activeConsumers(conch);
        if (!consumers.isEmpty()) {
            throw conflict("还有规则包在用这个授权器：" + String.join("、", consumers) + "。先停用它们");
        }
        AvailabilityRow current = availability.lock(conch);
        Instant at = clock.instant();
        if (current == null) {
            availability.insertDisabled(conch, at, actor, stored);
        } else if (current.disabled() || availability.updateDisabled(conch, at, actor, stored) != 1) {
            throw conflict(ALREADY_DISABLED);
        }
        auditService.record(actor, AuditAction.DISABLE, conch, currentVersion(rows), detail(stored));
        return view(conch, availability.lock(conch));
    }

    @Transactional
    public ConchAvailabilityView enable(String actor, String conch, String reason) {
        requireActor(actor);
        String stored = normalizeReason(reason);
        List<VersionRow> rows = lockExisting(conch);
        List<dev.hermit.common.validate.ValidationIssue> problems = authDependencies.currentConsumerProblems(rows);
        if (!problems.isEmpty()) {
            throw conflict(problems.get(0).message());
        }
        AvailabilityRow current = availability.lock(conch);
        if (current == null || !current.disabled() || availability.clearDisabled(conch) != 1) {
            throw conflict(NOT_DISABLED);
        }
        auditService.record(actor, AuditAction.ENABLE, conch, currentVersion(rows), detail(stored));
        return view(conch, availability.lock(conch));
    }

    public boolean isDisabled(String conch) {
        return conch != null && availability.isDisabled(conch);
    }

    private List<VersionRow> lockExisting(String conch) {
        // 停用主键必须是校验器接受的包名。大小写或尾随空格进 SQL 之前就 404，避免写成另一行。
        if (!ConchValidator.isConchName(conch)) {
            throw notFound();
        }
        List<VersionRow> rows = conches.lockConch(conch);
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

    private static ConchAvailabilityView view(String conch, AvailabilityRow row) {
        if (row == null || !row.disabled()) {
            return new ConchAvailabilityView(conch, false, null, null, null);
        }
        return new ConchAvailabilityView(conch, true, row.disabledAt(), row.disabledBy(), row.disabledReason());
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
