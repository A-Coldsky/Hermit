package dev.hermit.crab.report;

import dev.hermit.common.report.ReportBatch;

public record ReportPreview(ReportBatch batch, long dropped) {
}
