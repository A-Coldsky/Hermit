package dev.hermit.reef.shell;

import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/shells")
public class AgentShellController {

    private final ShellDistributionService distribution;

    public AgentShellController(ShellDistributionService distribution) {
        this.distribution = distribution;
    }

    @GetMapping("/manifest")
    public ResponseEntity<ApiResponse<List<ManifestEntry>>> manifest(
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {
        ShellDistributionService.ManifestNegotiation negotiation = distribution.manifest(ifNoneMatch);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ETAG, negotiation.etag());
        if (negotiation.notModified()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(ApiResponse.ok(negotiation.entries(), TraceIds.current(request)));
    }

    @GetMapping("/{shell}/{version:.+}")
    public ResponseEntity<byte[]> download(@PathVariable("shell") String shell, @PathVariable("version") String version) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/zip")
                .body(distribution.publishedZip(shell, version));
    }
}
