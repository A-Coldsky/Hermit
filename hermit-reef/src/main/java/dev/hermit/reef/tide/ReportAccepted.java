package dev.hermit.reef.tide;

/**
 * {@code failures} 是本批通过校验的条数。重复 trace 仍计入，库里只留第一条。
 */
public record ReportAccepted(Counts accepted) {

    public record Counts(int failures, int counts, int events) {
    }
}
