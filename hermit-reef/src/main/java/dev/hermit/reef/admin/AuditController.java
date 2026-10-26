package dev.hermit.reef.admin;

import dev.hermit.reef.audit.AuditPage;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public ApiResponse<AuditPage> audit(
            @RequestParam(name = "conch", required = false) String conch,
            @RequestParam(name = "page", defaultValue = "" + AuditService.DEFAULT_PAGE) int page,
            @RequestParam(name = "size", defaultValue = "" + AuditService.DEFAULT_SIZE) int size,
            HttpServletRequest request) {
        if (page < 1 || size < 1 || size > AuditService.MAX_SIZE) {
            throw ApiException.invalidInput();
        }
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw ApiException.invalidInput();
        }
        String conchFilter = conch == null || conch.isBlank() ? null : conch;
        return ApiResponse.ok(auditService.page(conchFilter, page, size), TraceIds.current(request));
    }
}
