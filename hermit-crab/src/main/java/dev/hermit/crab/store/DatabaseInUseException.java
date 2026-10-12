package dev.hermit.crab.store;

/** The H2 file is locked by another Crab process. */
public final class DatabaseInUseException extends RuntimeException {
    public static final String MESSAGE = "Crab 正在运行，请改用 /console/api";

    public DatabaseInUseException() {
        super(MESSAGE);
    }
}
