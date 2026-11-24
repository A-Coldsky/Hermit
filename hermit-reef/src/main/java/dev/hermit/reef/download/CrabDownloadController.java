package dev.hermit.reef.download;

import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
        CrabDownloadService.ResolvedJar jar = downloads.requireLocalJar();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(jar.fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(JAVA_ARCHIVE)
                .contentLength(jar.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(jar.path()));
    }
}
