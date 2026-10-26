package dev.hermit.reef.retention;

import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/retention")
public class RetentionController {

    private final RetentionService retentionService;
    private final AuditService auditService;

    public RetentionController(RetentionService retentionService, AuditService auditService) {
        this.retentionService = retentionService;
        this.auditService = auditService;
    }

    @PostMapping("/purge")
    public ApiResponse<PurgeCounts> purge(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication == null ? "" : authentication.getName();
        if (actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        PurgeCounts counts = retentionService.purge();
        auditService.record(actor, AuditAction.RETENTION_PURGE, null, null, auditDetail(counts));
        return ApiResponse.ok(counts, TraceIds.current(request));
    }

    public static String auditDetail(PurgeCounts counts) {
        return "failureReports=" + counts.failureReports()
                + ",rollbackEvents=" + counts.rollbackEvents()
                + ",loginFailures=" + counts.loginFailures();
    }
}
