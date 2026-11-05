package dev.hermit.crab.sync;

import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

import dev.hermit.common.error.ErrorCode;

/**
 * Maps Reef transport failures, and a manifest 401 or 403, to a stable console error. Any other
 * HTTP status from a Reef that did answer stays on the internal sync message, so a bad manifest
 * is not reported as unreachable or as an expired token.
 */
final class SyncErrors {

    private SyncErrors() {
    }

    static Optional<SyncError> clientError(SyncException ex) {
        if (ex == null) {
            return Optional.empty();
        }
        String detail = ex.getMessage();
        if ("reef request interrupted".equals(detail)) {
            return Optional.of(upstream("同步被中断。请稍后再次同步。"));
        }
        if (SyncReasons.http(401).equals(detail) || SyncReasons.http(403).equals(detail)) {
            return Optional.of(new SyncError(ErrorCode.AUTH_FAILED, SyncException.TOKEN_REJECTED));
        }
        if (!"reef request failed".equals(detail)) {
            return Optional.empty();
        }
        Throwable cause = ex.getCause();
        if (cause instanceof HttpTimeoutException) {
            return Optional.of(upstream("连接 Reef 超时。请确认本机到 Reef 的网络，以及 Reef 正在运行，然后再次同步。"));
        }
        if (cause instanceof UnknownHostException) {
            return Optional.of(upstream("无法解析 Reef 地址。请检查 config.yml 的 reef.url，然后再次同步。"));
        }
        return Optional.of(upstream("无法连接 Reef。请确认 Reef 已启动、reef.url 可从本机访问，然后再次同步。"));
    }

    private static SyncError upstream(String message) {
        return new SyncError(ErrorCode.UPSTREAM_ERROR, message);
    }
}
