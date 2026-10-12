package dev.hermit.reef.retention;

public record PurgeCounts(int failureReports, int rollbackEvents, int loginFailures) {
}
