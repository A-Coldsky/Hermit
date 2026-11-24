package dev.hermit.reef.conch;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.Locale;

public enum SubmissionStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN;

    public static SubmissionStatus parseOptional(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return SubmissionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                    "审批状态只能是 PENDING、APPROVED、REJECTED 或 WITHDRAWN");
        }
    }
}
