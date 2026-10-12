package dev.hermit.common.pkg;

public final class PackageVerificationException extends RuntimeException {

    private final PackageReason reason;

    public PackageVerificationException(PackageReason reason, String message) {
        this(reason, message, null);
    }

    public PackageVerificationException(PackageReason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public PackageReason reason() {
        return reason;
    }
}
