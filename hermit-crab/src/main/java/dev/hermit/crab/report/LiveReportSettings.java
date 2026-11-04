package dev.hermit.crab.report;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Report URL, token, and enabled flag shared by the queue and the sender.
 * Replacing the value takes effect on the next record or send without rebuilding those objects.
 */
public final class LiveReportSettings {
    private final AtomicReference<ReportSettings> current;

    public LiveReportSettings(ReportSettings initial) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "settings"));
    }

    public ReportSettings get() {
        return current.get();
    }

    public void set(ReportSettings next) {
        current.set(Objects.requireNonNull(next, "settings"));
    }
}
