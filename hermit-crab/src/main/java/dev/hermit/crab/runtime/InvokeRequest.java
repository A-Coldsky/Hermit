package dev.hermit.crab.runtime;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.CallChannel;
import dev.hermit.crab.store.Origin;

import java.util.Map;
import java.util.Optional;

/**
 * 一次能力调用的全部入参。HTTP 和 MCP 入口各自填自己用得到的字段，其余保持默认。
 *
 * <ul>
 *   <li>{@code bodySha256}：幂等键对应的原始请求体摘要；null 只和另一个 null 相等（进程内调用）。</li>
 *   <li>{@code channel}：请求进来的入口，null 记作 {@link CallChannel#HTTP}。</li>
 *   <li>{@code httpConfirmed}、{@code confirmToken}：HTTP 的 {@code X-Hermit-Confirm} 和
 *       {@code X-Hermit-Confirm-Token}；MCP 的确认走 elicitation，不看这两项。</li>
 *   <li>{@code confirmedAgainst}、{@code confirmedOrigin}：调用方已经据此做过确认的那份规则包和来源；
 *       注册表里的版本或来源和它不一样时拒绝。</li>
 *   <li>{@code httpMethod}：HTTP 网关传入的请求方法，在原子读出当前文档后校验 HTTP 方法，消除预读与执行之间的竞态。</li>
 * </ul>
 */
public record InvokeRequest(
        ApiKeyPrincipal principal,
        String conch,
        String action,
        Map<String, Object> input,
        Optional<String> account,
        Optional<String> idempotencyKey,
        String bodySha256,
        CallChannel channel,
        String traceId,
        boolean httpConfirmed,
        ConchDoc confirmedAgainst,
        Origin confirmedOrigin,
        String confirmToken,
        String httpMethod
) {
    public static InvokeRequest of(
            ApiKeyPrincipal principal, String conch, String action, Map<String, Object> input, String traceId) {
        return new InvokeRequest(principal, conch, action, input, Optional.empty(), Optional.empty(),
                null, CallChannel.HTTP, traceId, false, null, null, null, null);
    }

    public InvokeRequest account(Optional<String> value) {
        return new InvokeRequest(principal, conch, action, input, value, idempotencyKey,
                bodySha256, channel, traceId, httpConfirmed, confirmedAgainst, confirmedOrigin, confirmToken, httpMethod);
    }

    public InvokeRequest idempotency(Optional<String> key, String sha256) {
        return new InvokeRequest(principal, conch, action, input, account, key,
                sha256, channel, traceId, httpConfirmed, confirmedAgainst, confirmedOrigin, confirmToken, httpMethod);
    }

    public InvokeRequest channel(CallChannel value) {
        return new InvokeRequest(principal, conch, action, input, account, idempotencyKey,
                bodySha256, value, traceId, httpConfirmed, confirmedAgainst, confirmedOrigin, confirmToken, httpMethod);
    }

    public InvokeRequest httpConfirm(boolean confirmed, String token) {
        return new InvokeRequest(principal, conch, action, input, account, idempotencyKey,
                bodySha256, channel, traceId, confirmed, confirmedAgainst, confirmedOrigin, token, httpMethod);
    }

    public InvokeRequest confirmedAgainst(ConchDoc doc, Origin origin) {
        return new InvokeRequest(principal, conch, action, input, account, idempotencyKey,
                bodySha256, channel, traceId, httpConfirmed, doc, origin, confirmToken, httpMethod);
    }

    public InvokeRequest httpMethod(String method) {
        return new InvokeRequest(principal, conch, action, input, account, idempotencyKey,
                bodySha256, channel, traceId, httpConfirmed, confirmedAgainst, confirmedOrigin, confirmToken, method);
    }
}
