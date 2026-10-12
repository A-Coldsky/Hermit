package dev.hermit.reef.tide;

import java.math.BigDecimal;
import java.util.List;

public record VersionCompare(String shell, List<VersionStats> versions) {

    public record VersionStats(String version, long calls, long failures, BigDecimal failureRate) {
    }
}
