package dev.hermit.reef.notice;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.Locale;

public enum AnnouncementLevel {
    INFO,
    WARNING,
    IMPORTANT;

    public static AnnouncementLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INFO;
        }
        try {
            return AnnouncementLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, "公告级别只能是 INFO、WARNING 或 IMPORTANT");
        }
    }
}
