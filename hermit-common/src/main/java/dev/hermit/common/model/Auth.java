package dev.hermit.common.model;

import java.util.List;

public record Auth(List<Step> steps, AliveCheck aliveCheck) {
    public Auth {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
