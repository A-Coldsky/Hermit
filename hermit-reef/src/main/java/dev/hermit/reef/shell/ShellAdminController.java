package dev.hermit.reef.shell;

import dev.hermit.common.validate.ShellDiff;
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
public class ShellAdminController {

    private final SigningKeyStore signingKeys;
    private final ShellUploadService uploads;
    private final ShellPublishService publishService;
    private final ShellCatalogService catalog;

    public ShellAdminController(SigningKeyStore signingKeys, ShellUploadService uploads,
            ShellPublishService publishService, ShellCatalogService catalog) {
        this.signingKeys = signingKeys;
        this.uploads = uploads;
        this.publishService = publishService;
        this.catalog = catalog;
    }

    @GetMapping("/signing-key")
    public ApiResponse<SigningKeyResponse> signingKey(HttpServletRequest request) {
        return ApiResponse.ok(new SigningKeyResponse(signingKeys.publicPem()), TraceIds.current(request));
    }

    @PostMapping("/shells")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (file.getSize() > ShellPaths.MAX_UPLOAD_BYTES) {
            throw ShellRequestException.invalid(null);
        }
        byte[] body;
        try {
            body = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        return ApiResponse.ok(uploads.upload(actor(), body), TraceIds.current(request));
    }

    @GetMapping("/shells")
    public ApiResponse<List<ShellSummary>> list(HttpServletRequest request) {
        return ApiResponse.ok(catalog.list(), TraceIds.current(request));
    }

    @GetMapping("/shells/{shell}")
    public ApiResponse<ShellDetail> detail(@PathVariable("shell") String shell, HttpServletRequest request) {
        return ApiResponse.ok(catalog.detail(shell), TraceIds.current(request));
    }

    @GetMapping("/shells/{shell}/versions")
    public ApiResponse<List<ShellVersionItem>> versions(@PathVariable("shell") String shell, HttpServletRequest request) {
        return ApiResponse.ok(catalog.versions(shell), TraceIds.current(request));
    }

    @GetMapping("/shells/{shell}/versions/{version:.+}/yaml")
    public ResponseEntity<byte[]> yaml(@PathVariable("shell") String shell, @PathVariable("version") String version) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(catalog.yaml(shell, version));
    }

    @GetMapping("/shells/{shell}/diff")
    public ApiResponse<ShellDiff> diff(@PathVariable("shell") String shell,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        return ApiResponse.ok(catalog.diff(shell, from, to), TraceIds.current(request));
    }

    @PostMapping("/shells/{shell}/versions/{version:.+}/publish")
    public ApiResponse<PublishResponse> publish(@PathVariable("shell") String shell,
            @PathVariable("version") String version, HttpServletRequest request) {
        return ApiResponse.ok(publishService.publish(actor(), shell, version), TraceIds.current(request));
    }

    @PostMapping("/shells/{shell}/rollback")
    public ApiResponse<RollbackResponse> rollback(@PathVariable("shell") String shell,
            @RequestBody(required = false) RollbackBody body, HttpServletRequest request) {
        String version = body == null ? null : body.version();
        return ApiResponse.ok(publishService.rollback(actor(), shell, version), TraceIds.current(request));
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
}
