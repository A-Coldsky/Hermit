package dev.hermit.reef.agent;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentVersionController {

    private final ReefProperties properties;

    public AgentVersionController(ReefProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/agent/version")
    public ApiResponse<VersionResponse> version(HttpServletRequest request) {
        return ApiResponse.ok(
                new VersionResponse(properties.latestCrabVersionOrNull(), properties.crabDownloadUrlOrNull()),
                TraceIds.current(request));
    }
}
