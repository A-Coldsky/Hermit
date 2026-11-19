package dev.hermit.crab.sync;

import java.util.Optional;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.crab.tls.TransportFailure;

/**
 * 把同步失败换成给人看的句子。传输失败和清单 401/403 会替换最近一次成功。
 * Reef 已经返回的其它 HTTP 状态，以及读不出的清单，另有中文，但不说成连不上或令牌失效，也不替换最近一次成功。
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
        Optional<SyncError> answered = answered(detail);
        if (answered.isPresent()) {
            return answered;
        }
        if (!"reef request failed".equals(detail)) {
            return Optional.empty();
        }
        Throwable cause = ex.getCause();
        if (TransportFailure.isTimeout(cause)) {
            return Optional.of(upstream("连接 Reef 超时。请确认本机到 Reef 的网络，以及 Reef 正在运行，然后再次同步。"));
        }
        if (TransportFailure.isDns(cause)) {
            return Optional.of(upstream("无法解析 Reef 地址。请检查 config.yml 的 reef.url，然后再次同步。"));
        }
        TransportFailure.View view = TransportFailure.classify(cause, ex.targetHost(), ex.targetPort());
        if (explained(view.kind())) {
            return Optional.of(upstream(view.message()));
        }
        return Optional.of(upstream("无法连接 Reef。请确认 Reef 已启动、reef.url 可从本机访问，然后再次同步。"));
    }

    /**
     * Reef 已经给出 HTTP 状态，或清单读不出来。句子里只有状态码，没有正文。
     * 这不是连不上，也不是令牌失效，调用方不要把它记成最近一次成功被覆盖的网络失败。
     */
    private static Optional<SyncError> answered(String detail) {
        if ("manifest rejected".equals(detail) || "manifest unreadable".equals(detail)) {
            return Optional.of(new SyncError(ErrorCode.INTERNAL, "Reef 返回的清单无法读取。"));
        }
        if (detail != null && detail.startsWith("HTTP_")) {
            String status = detail.substring("HTTP_".length());
            if (!status.isEmpty() && status.chars().allMatch(Character::isDigit)) {
                return Optional.of(new SyncError(ErrorCode.INTERNAL, "Reef 返回了 HTTP " + status + "。"));
            }
        }
        return Optional.empty();
    }

    /** 传输失败和令牌被拒才替换最近一次成功。清单和其它 HTTP 状态只展示，不替换。 */
    static boolean replacesLastSuccess(SyncError error) {
        return error != null
                && (error.code() == ErrorCode.UPSTREAM_ERROR || error.code() == ErrorCode.AUTH_FAILED);
    }

    private static boolean explained(TransportFailure.Kind kind) {
        return switch (kind) {
            case REFUSED, NO_RESPONSE, HEADER_LIMIT, TLS_UNTRUSTED, TLS_INTERMEDIATE, TLS_HOSTNAME,
                    TLS_EXPIRED, TLS_PROTOCOL, TLS_CIPHER, TLS_CERT_ALGORITHM, TLS_SNI, TLS_OTHER -> true;
            case DNS, TIMEOUT, OTHER -> false;
        };
    }

    private static SyncError upstream(String message) {
        return new SyncError(ErrorCode.UPSTREAM_ERROR, message);
    }
}
