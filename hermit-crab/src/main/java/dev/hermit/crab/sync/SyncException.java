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
     * Set when this attempt should be shown and stored as the latest sync. That covers transport
     * failures and a manifest HTTP 401 or 403. Empty for every other HTTP status and for an
     * unreadable manifest: those keep their internal reason and do not replace the last success.
     */
    public Optional<SyncError> clientError() {
        return SyncErrors.clientError(this);
    }
}
