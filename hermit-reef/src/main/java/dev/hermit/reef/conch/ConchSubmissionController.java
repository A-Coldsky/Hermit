package dev.hermit.reef.conch;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/submissions")
public class ConchSubmissionController {

    private final ConchSubmissionService submissions;

    public ConchSubmissionController(ConchSubmissionService submissions) {
        this.submissions = submissions;
    }

    @GetMapping
    public ApiResponse<List<SubmissionSummary>> list(
            @RequestParam(name = "status", required = false) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(submissions.list(actor(), isAdmin(), status), TraceIds.current(request));
    }

    @GetMapping("/pending-count")
    public ApiResponse<PendingCountResponse> pendingCount(HttpServletRequest request) {
        return ApiResponse.ok(
                new PendingCountResponse(submissions.pendingCount(actor(), isAdmin())),
                TraceIds.current(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<SubmissionDetail> detail(@PathVariable("id") long id, HttpServletRequest request) {
        return ApiResponse.ok(submissions.detail(id, actor(), isAdmin()), TraceIds.current(request));
    }

    @GetMapping("/{id}/yaml")
    public ResponseEntity<byte[]> yaml(@PathVariable("id") long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(submissions.yaml(id, actor(), isAdmin()));
    }

    @PostMapping
    public ApiResponse<SubmissionDetail> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "note", required = false) String note,
            HttpServletRequest request) {
        if (file.getSize() > ConchPaths.MAX_UPLOAD_BYTES) {
            throw ConchRequestException.invalid(null);
        }
        byte[] body;
        try {
            body = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        return ApiResponse.ok(submissions.submit(actor(), body, note), TraceIds.current(request));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<SubmissionDetail> withdraw(@PathVariable("id") long id, HttpServletRequest request) {
        return ApiResponse.ok(submissions.withdraw(id, actor()), TraceIds.current(request));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<SubmissionDetail> approve(
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewBody body,
            HttpServletRequest request) {
        requireAdmin();
        String comment = body == null ? null : body.comment();
        return ApiResponse.ok(submissions.approve(id, actor(), comment), TraceIds.current(request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<SubmissionDetail> reject(
            @PathVariable("id") long id,
            @RequestBody(required = false) ReviewBody body,
            HttpServletRequest request) {
        requireAdmin();
        String comment = body == null ? null : body.comment();
        return ApiResponse.ok(submissions.reject(id, actor(), comment), TraceIds.current(request));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return authentication.getName();
    }

    private static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static void requireAdmin() {
        if (!isAdmin()) {
            throw new ApiException(403, ReefErrorCode.FORBIDDEN, ReefErrorCode.FORBIDDEN.message());
        }
    }

    public record ReviewBody(String comment) {
    }
}
