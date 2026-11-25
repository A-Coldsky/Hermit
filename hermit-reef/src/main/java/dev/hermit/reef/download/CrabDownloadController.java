package dev.hermit.reef.download;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/crab")
public class CrabDownloadController {

    private static final MediaType JAVA_ARCHIVE = MediaType.parseMediaType("application/java-archive");

    private final CrabDownloadService downloads;

    public CrabDownloadController(CrabDownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping("/download-info")
    public ApiResponse<CrabDownloadInfo> info(HttpServletRequest request) {
        return ApiResponse.ok(downloads.info(), TraceIds.current(request));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download() {
        CrabDownloadService.DownloadableJar jar = downloads.requireDownloadable();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(jar.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(JAVA_ARCHIVE)
                .contentLength(jar.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(jar.resource());
    }

    @PostMapping("/upload")
    public ApiResponse<CrabDownloadInfo> upload(
            @RequestParam(name = "name", required = false) String fileName,
            HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength < 0) {
            String rawLength = request.getHeader(HttpHeaders.CONTENT_LENGTH);
            if (rawLength != null) {
                try {
                    declaredLength = Long.parseLong(rawLength.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        CrabDownloadInfo updated = downloads.upload(
                fileName,
                declaredLength,
                request.getInputStream(),
                actor());
        return ApiResponse.ok(updated, TraceIds.current(request));
    }

    @PostMapping("/rollback")
    public ApiResponse<CrabDownloadInfo> rollback(HttpServletRequest request) {
        return ApiResponse.ok(downloads.rollback(actor()), TraceIds.current(request));
    }

    @DeleteMapping("/override")
    public ApiResponse<CrabDownloadInfo> resetToDefault(HttpServletRequest request) {
        return ApiResponse.ok(downloads.resetToDefault(actor()), TraceIds.current(request));
    }

    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return authentication.getName();
    }
}
