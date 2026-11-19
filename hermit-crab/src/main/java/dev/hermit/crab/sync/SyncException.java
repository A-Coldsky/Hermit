package dev.hermit.crab.sync;

import java.util.Optional;

public final class SyncException extends RuntimeException {

    /** Manifest HTTP 401 or 403. The bootstrap file is left in place. */
    public static final String TOKEN_REJECTED = "令牌已失效，请重新导入引导文件。";

    private final String targetHost;
    private final int targetPort;

    public SyncException(String message) {
        this(message, null, null, -1);
    }

    public SyncException(String message, Throwable cause) {
        this(message, cause, null, -1);
    }

    SyncException(String message, Throwable cause, String host, int port) {
        super(message, cause);
        this.targetHost = host;
        this.targetPort = port;
    }

    String targetHost() {
        return targetHost;
    }

    int targetPort() {
        return targetPort;
    }

    /**
     * 给人看的同步失败。传输失败和清单 401/403 会替换最近一次成功。
     * Reef 已经返回的其它 HTTP 状态，以及读不出的清单，也有中文句子，但是
     * {@link SyncErrors#replacesLastSuccess} 为 false，不替换最近一次成功，也不说成连不上。
     */
    public Optional<SyncError> clientError() {
        return SyncErrors.clientError(this);
    }
}
