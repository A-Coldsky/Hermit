package dev.hermit.reef.admin;

import dev.hermit.reef.web.ApiException;
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
    private final AdminUsers users;

    public AdminAuthController(LoginService loginService, AdminUsers users) {
        this.loginService = loginService;
        this.users = users;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        httpResponse.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String traceId = TraceIds.current(httpRequest);
        LoginAttempt attempt = loginService.authenticate(request.username(), request.password());
        if (attempt instanceof LoginAttempt.Success success) {
            return ResponseEntity.ok(ApiResponse.ok(success.login(), traceId));
        }
        LoginAttempt.Failure failure = (LoginAttempt.Failure) attempt;
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(ReefErrorCode.UNAUTHORIZED, failure.message(), failure.data(), traceId));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AdminAccount account = users.find(authentication.getName());
        if (account == null || account.disabled()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return ApiResponse.ok(new MeResponse(account.username(), account.role()), TraceIds.current(request));
    }
}
