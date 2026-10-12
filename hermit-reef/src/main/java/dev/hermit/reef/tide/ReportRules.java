package dev.hermit.reef.tide;

import dev.hermit.common.report.CountReport;
import dev.hermit.common.report.FailureReport;
import dev.hermit.common.report.ReportBatch;
import dev.hermit.common.report.RollbackEvent;
import dev.hermit.common.version.SemVer;
import java.util.regex.Pattern;

/**
 * shell / 能力名与 hermit-common 的 {@code ^[a-z][a-z0-9_]{1,63}$} 一致。
 * 超长 summary 在 {@link FailureReport} 构造时已按码点截到 200，这里不再拒绝。
 */
final class ReportRules {

    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int MAX_FAILURES = 500;
    static final int MAX_COUNTS = 500;
    static final int MAX_EVENTS = 50;

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final int MAX_CRAB_VERSION = 64;
    private static final int MAX_TRACE_ID = 128;
    private static final int MAX_VERSION = 64;
    private static final int MAX_STEP_TYPE = 64;
    private static final int MAX_REASON = 1024;

    private ReportRules() {
    }

    static void check(ReportBatch batch, String instanceHeader) {
        if (batch.failures().size() > MAX_FAILURES
                || batch.counts().size() > MAX_COUNTS
                || batch.events().size() > MAX_EVENTS) {
            throw TideErrors.unprocessable();
        }
        if (instanceHeader == null || !instanceHeader.equals(batch.instanceId())) {
            throw TideErrors.unprocessable();
        }
        if (batch.crabVersion().length() > MAX_CRAB_VERSION) {
            throw TideErrors.unprocessable();
        }
        for (FailureReport failure : batch.failures()) {
            requireName(failure.shell());
            requireName(failure.action());
            requireVersion(failure.shellVersion());
            if (failure.traceId().length() > MAX_TRACE_ID || length(failure.stepType()) > MAX_STEP_TYPE) {
                throw TideErrors.unprocessable();
            }
        }
        for (CountReport count : batch.counts()) {
            requireName(count.shell());
            requireName(count.action());
            requireVersion(count.shellVersion());
            if (count.success() < 0 || count.failure() < 0 || count.avgMillis() < 0 || count.p95Millis() < 0) {
                throw TideErrors.unprocessable();
            }
        }
        for (RollbackEvent event : batch.events()) {
            requireName(event.shell());
            requireVersion(event.fromVersion());
            requireVersion(event.toVersion());
            if (event.reason().length() > MAX_REASON) {
                throw TideErrors.unprocessable();
            }
        }
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw TideErrors.unprocessable();
        }
    }

    private static void requireVersion(String version) {
        if (version == null || version.length() > MAX_VERSION) {
            throw TideErrors.unprocessable();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw TideErrors.unprocessable();
        }
        if (!parsed.toString().equals(version)) {
            throw TideErrors.unprocessable();
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
