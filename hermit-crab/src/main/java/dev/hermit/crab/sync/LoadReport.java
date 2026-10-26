package dev.hermit.crab.sync;

import java.util.List;

public record LoadReport(List<String> loaded, List<ConchOutcome> failures) {

    public LoadReport {
        loaded = loaded == null ? List.of() : List.copyOf(loaded);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
