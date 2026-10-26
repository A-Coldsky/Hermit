package dev.hermit.reef.conch;

import dev.hermit.common.validate.ConchDiff;
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
@RequestMapping("/api/admin")
public class ConchAdminController {

    private final SigningKeyStore signingKeys;
    private final ConchUploadService uploads;
    private final ConchPublishService publishService;
    private final ConchAvailabilityService availability;
    private final ConchCatalogService catalog;

    public ConchAdminController(SigningKeyStore signingKeys, ConchUploadService uploads,
            ConchPublishService publishService, ConchAvailabilityService availability, ConchCatalogService catalog) {
        this.signingKeys = signingKeys;
        this.uploads = uploads;
        this.publishService = publishService;
        this.availability = availability;
        this.catalog = catalog;
    }

    @GetMapping("/signing-key")
    public ApiResponse<SigningKeyResponse> signingKey(HttpServletRequest request) {
        return ApiResponse.ok(new SigningKeyResponse(signingKeys.publicPem()), TraceIds.current(request));
    }

    @PostMapping("/conches")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (file.getSize() > ConchPaths.MAX_UPLOAD_BYTES) {
            throw ConchRequestException.invalid(null);
        }
        byte[] body;
        try {
            body = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        return ApiResponse.ok(uploads.upload(actor(), body), TraceIds.current(request));
    }

    @GetMapping("/conches")
    public ApiResponse<List<ConchSummary>> list(HttpServletRequest request) {
        return ApiResponse.ok(catalog.list(), TraceIds.current(request));
    }

    @GetMapping("/conches/{conch}")
    public ApiResponse<ConchDetail> detail(@PathVariable("conch") String conch, HttpServletRequest request) {
        return ApiResponse.ok(catalog.detail(conch), TraceIds.current(request));
    }

    @GetMapping("/conches/{conch}/versions")
    public ApiResponse<List<ConchVersionItem>> versions(@PathVariable("conch") String conch, HttpServletRequest request) {
        return ApiResponse.ok(catalog.versions(conch), TraceIds.current(request));
    }

    @GetMapping("/conches/{conch}/versions/{version:.+}/yaml")
    public ResponseEntity<byte[]> yaml(@PathVariable("conch") String conch, @PathVariable("version") String version) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(catalog.yaml(conch, version));
    }

    @GetMapping("/conches/{conch}/diff")
    public ApiResponse<ConchDiff> diff(@PathVariable("conch") String conch,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        return ApiResponse.ok(catalog.diff(conch, from, to), TraceIds.current(request));
    }

    @PostMapping("/conches/{conch}/versions/{version:.+}/publish")
    public ApiResponse<PublishResponse> publish(@PathVariable("conch") String conch,
            @PathVariable("version") String version, HttpServletRequest request) {
        return ApiResponse.ok(publishService.publish(actor(), conch, version), TraceIds.current(request));
    }

    @PostMapping("/conches/{conch}/disable")
    public ApiResponse<ConchAvailabilityView> disable(@PathVariable("conch") String conch,
            @RequestBody(required = false) ReasonBody body, HttpServletRequest request) {
        String reason = body == null ? null : body.reason();
        return ApiResponse.ok(availability.disable(actor(), conch, reason), TraceIds.current(request));
    }

    @PostMapping("/conches/{conch}/enable")
    public ApiResponse<ConchAvailabilityView> enable(@PathVariable("conch") String conch,
            @RequestBody(required = false) ReasonBody body, HttpServletRequest request) {
        String reason = body == null ? null : body.reason();
        return ApiResponse.ok(availability.enable(actor(), conch, reason), TraceIds.current(request));
    }

    @PostMapping("/conches/{conch}/rollback")
    public ApiResponse<RollbackResponse> rollback(@PathVariable("conch") String conch,
            @RequestBody(required = false) RollbackBody body, HttpServletRequest request) {
        String version = body == null ? null : body.version();
        return ApiResponse.ok(publishService.rollback(actor(), conch, version), TraceIds.current(request));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return authentication.getName();
    }

    public record RollbackBody(String version) {
    }

    public record ReasonBody(String reason) {
    }
}
