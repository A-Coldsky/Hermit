package dev.hermit.reef.admin;

import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final LoginService loginService;

    public AdminAuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String traceId = TraceIds.current(httpRequest);
        return loginService.login(request.username(), request.password())
                .map(body -> ResponseEntity.ok(ApiResponse.ok(body, traceId)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.fail(ReefErrorCode.UNAUTHORIZED, LoginService.FAILURE_MESSAGE, traceId)));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ApiResponse.ok(new MeResponse(authentication.getName()), TraceIds.current(request));
    }
}
