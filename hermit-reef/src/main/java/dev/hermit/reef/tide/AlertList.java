package dev.hermit.reef.tide;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record AlertList(List<AlertItem> alerts) {

    public record AlertItem(
            long id,
            Instant createdAt,
            String kind,
            String shell,
            String shellVersion,
            JsonNode detail,
            Instant acknowledgedAt,
            String acknowledgedBy) {
    }
}
