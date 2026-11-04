package dev.hermit.reef.admin;

import dev.hermit.reef.conch.SigningKeyStore;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AgentTokenController {

    private final AgentTokenService tokens;
    private final SigningKeyStore signingKeys;

    public AgentTokenController(AgentTokenService tokens, SigningKeyStore signingKeys) {
        this.tokens = tokens;
        this.signingKeys = signingKeys;
    }

    @GetMapping("/tokens")
    public ApiResponse<List<AgentTokenView>> list(
            @RequestParam(name = "all", defaultValue = "false") boolean all,
            HttpServletRequest request) {
        Authentication authentication = AdminCaller.current();
        return ApiResponse.ok(
                tokens.list(authentication.getName(), AdminCaller.admin(authentication), all),
                TraceIds.current(request));
    }

    @PostMapping("/tokens")
    public ApiResponse<IssuedAgentToken> create(@Valid @RequestBody CreateTokenRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        IssuedAgentToken issued = tokens.create(AdminCaller.current().getName(), body.name(), signingKeys.publicPem());
        return ApiResponse.ok(issued, TraceIds.current(request));
    }

    @DeleteMapping("/tokens/{id}")
    public ApiResponse<AgentTokenView> revoke(@PathVariable("id") long id, HttpServletRequest request) {
        Authentication authentication = AdminCaller.current();
        return ApiResponse.ok(
                tokens.revoke(authentication.getName(), AdminCaller.admin(authentication), id),
                TraceIds.current(request));
    }

    @GetMapping("/crabs")
    public ApiResponse<List<CrabInstanceView>> crabs(HttpServletRequest request) {
        Authentication authentication = AdminCaller.current();
        return ApiResponse.ok(
                tokens.crabs(authentication.getName(), AdminCaller.admin(authentication)),
                TraceIds.current(request));
    }

    public record CreateTokenRequest(@NotBlank @Size(max = 64) String name) {
    }
}
