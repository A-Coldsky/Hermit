package dev.hermit.reef.tide;

import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tide")
public class TideController {

    private final TideQueryService tideQueryService;

    public TideController(TideQueryService tideQueryService) {
        this.tideQueryService = tideQueryService;
    }

    @GetMapping("/summary")
    public ApiResponse<TideSummary> summary(
            @RequestParam(name = "conch", required = false) String conch,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "tz", required = false) String tz,
            HttpServletRequest request) {
        return ApiResponse.ok(tideQueryService.summary(conch, from, to, bucket, tz), TraceIds.current(request));
    }

    @GetMapping("/failures")
    public ApiResponse<FailureDrilldown> failures(
            @RequestParam(name = "conch", required = false) String conch,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        return ApiResponse.ok(tideQueryService.failures(conch, version, action, from, to), TraceIds.current(request));
    }

    @GetMapping("/compare")
    public ApiResponse<VersionCompare> compare(
            @RequestParam(name = "conch", required = false) String conch,
            @RequestParam(name = "versions", required = false) String versions,
            HttpServletRequest request) {
        return ApiResponse.ok(tideQueryService.compare(conch, versions), TraceIds.current(request));
    }

    @GetMapping("/rollbacks")
    public ApiResponse<RollbackList> rollbacks(
            @RequestParam(name = "conch", required = false) String conch,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            HttpServletRequest request) {
        return ApiResponse.ok(tideQueryService.rollbacks(conch, from, to), TraceIds.current(request));
    }
}
