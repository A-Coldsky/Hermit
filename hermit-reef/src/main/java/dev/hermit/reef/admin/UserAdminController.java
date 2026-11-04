package dev.hermit.reef.admin;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {

    private final AdminUsers users;

    public UserAdminController(AdminUsers users) {
        this.users = users;
    }

    @PostMapping("/me/password")
    public ApiResponse<UserSummary> changePassword(@Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String actor = AdminCaller.current().getName();
        return ApiResponse.ok(users.changePassword(actor, body.oldPassword(), body.newPassword()),
                TraceIds.current(request));
    }

    @GetMapping("/users")
    public ApiResponse<java.util.List<UserSummary>> list(HttpServletRequest request) {
        return ApiResponse.ok(users.list(), TraceIds.current(request));
    }

    @PostMapping("/users")
    public ApiResponse<UserSummary> create(@Valid @RequestBody CreateUserRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(
                users.create(AdminCaller.current().getName(), body.username(), body.password(), body.role()),
                TraceIds.current(request));
    }

    @PatchMapping("/users/{username}")
    public ApiResponse<UserSummary> update(@PathVariable("username") String username,
            @RequestBody UpdateUserRequest body, HttpServletRequest request) {
        if (body == null) {
            throw ApiException.invalidInput();
        }
        return ApiResponse.ok(
                users.update(AdminCaller.current().getName(), username, body.role(), body.disabled()),
                TraceIds.current(request));
    }

    @PostMapping("/users/{username}/password")
    public ApiResponse<UserSummary> resetPassword(@PathVariable("username") String username,
            @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(users.resetPassword(AdminCaller.current().getName(), username, body.password()),
                TraceIds.current(request));
    }

    public record ChangePasswordRequest(
            @NotBlank @MaxUtf8Bytes(72) String oldPassword,
            @NotBlank @MaxUtf8Bytes(72) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest";
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @MaxUtf8Bytes(72) String password,
            @NotNull AdminRole role) {

        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + ", role=" + role + "]";
        }
    }

    public record UpdateUserRequest(AdminRole role, Boolean disabled) {
    }

    public record ResetPasswordRequest(@NotBlank @MaxUtf8Bytes(72) String password) {

        @Override
        public String toString() {
            return "ResetPasswordRequest";
        }
    }
}
