package dev.hermit.reef.conch;

import dev.hermit.reef.security.AgentAccessFilter;
import dev.hermit.reef.security.AgentPrincipal;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ApiResponse;
import dev.hermit.reef.web.ReefErrorCode;
import dev.hermit.reef.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供已连接 Reef 的 Crab 控制台手动将本地规则包提交至 Reef 待审批队列。
 * 要求请求携带归属个人账号的连接令牌（{@code ownerUsername != null}）；
 * 配置文件中的无主静态连接令牌返回 403，保证每条提交都有明确的提交人归属。
 */
@RestController
@RequestMapping("/agent/submissions")
public class AgentSubmissionController {

    static final String OWNER_REQUIRED = "当前连接令牌为无归属用户的静态令牌，无法提交审批；请在 Reef 个人设置生成个人连接令牌重新连接";

    private final ConchSubmissionService submissions;

    public AgentSubmissionController(ConchSubmissionService submissions) {
        this.submissions = submissions;
    }

    @PostMapping
    public ApiResponse<SubmissionDetail> submit(
            @RequestBody(required = false) SubmitRequest body,
            HttpServletRequest request
    ) {
        AgentPrincipal principal = AgentAccessFilter.principal(request);
        if (principal == null) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        String owner = principal.username();
        if (principal.tokenId() == null || owner == null || owner.isBlank()) {
            throw new ApiException(403, ReefErrorCode.FORBIDDEN, OWNER_REQUIRED);
        }
        if (body == null || body.yaml() == null || body.yaml().isBlank()) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, "yaml 不能为空");
        }
        byte[] yamlBytes = body.yaml().getBytes(StandardCharsets.UTF_8);
        if (yamlBytes.length > ConchPaths.MAX_UPLOAD_BYTES) {
            throw ConchRequestException.invalid(null);
        }
        return ApiResponse.ok(submissions.submit(owner, yamlBytes, body.note()), TraceIds.current(request));
    }

    public record SubmitRequest(String yaml, String note) {
    }
}
