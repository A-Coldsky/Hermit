package dev.hermit.reef.tide;

import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public ApiResponse<AlertList> list(
            @RequestParam(name = "status", required = false) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(alertService.list(status), TraceIds.current(request));
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<AlertList.AlertItem> ack(@PathVariable("id") long id, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ApiResponse.ok(alertService.ack(id, authentication.getName()), TraceIds.current(request));
    }
}
