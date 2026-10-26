package dev.hermit.crab.sync;

import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

import dev.hermit.common.error.ErrorCode;

/**
 * Maps Reef transport failures to a stable console error. HTTP status from a Reef that did answer
 * stays on the internal sync message so a bad manifest is not reported as "unreachable".
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
