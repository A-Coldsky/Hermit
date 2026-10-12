package dev.hermit.crab.report;

public record FlushResult(boolean attempted, boolean accepted, int status) {

    public static FlushResult skipped() {
        return new FlushResult(false, false, 0);
    }

    public static FlushResult accepted(int status) {
        return new FlushResult(true, true, status);
    }

    public static FlushResult rejected(int status) {
        return new FlushResult(true, false, status);
    }
}
